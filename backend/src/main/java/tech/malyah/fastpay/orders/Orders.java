package tech.malyah.fastpay.orders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.catalog.Catalog;
import tech.malyah.fastpay.catalog.Catalog.OrderableProduct;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.jobs.JobRequest;
import tech.malyah.fastpay.jobs.Jobs;
import tech.malyah.fastpay.jobs.Jobs.EffectState;
import tech.malyah.fastpay.orders.domain.OrderRules;
import tech.malyah.fastpay.orders.domain.OrderRules.CurrentProduct;
import tech.malyah.fastpay.orders.domain.OrderRules.DraftLine;
import tech.malyah.fastpay.orders.domain.OrderRules.RawLine;
import tech.malyah.fastpay.orders.domain.OrderRules.Review;
import tech.malyah.fastpay.orders.infrastructure.JdbcOrderStore;
import tech.malyah.fastpay.orders.infrastructure.JdbcOrderStore.LineRow;
import tech.malyah.fastpay.orders.infrastructure.JdbcOrderStore.OrderRow;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.OrderTarget;

/**
 * Envio de pedido pela equipe (H16, FR-14–FR-16, CA-05–CA-07, D-03, D-04). O garçom monta o rascunho no aparelho e
 * confirma "Enviar pedido"; antes disso não há consumo nem produção. O envio, numa transação só:
 * <ol>
 * <li>trava a comanda (contrato com o fechamento, {@link Tabs#lockForOrderInCurrentUnit}): comanda encerrada recusa e
 * o rascunho fica no aparelho;</li>
 * <li>trava para leitura os produtos e confere disponibilidade e preço: esgotado ou fora do catálogo mantém o pedido
 * inteiro no rascunho; preço diferente do visto pede reconfirmação (nada é gravado em nenhum dos casos);</li>
 * <li>grava o pedido, as linhas com o preço do envio (snapshot imutável), a alocação de cada linha na comanda, a
 * auditoria e o job de encaminhamento à produção ({@link #FORWARDING_JOB}).</li>
 * </ol>
 * A chave de operação gerada pelo aparelho torna o envio idempotente: repetir por clique duplo, timeout ou reconexão
 * devolve o mesmo pedido, e o aparelho pode consultar a operação antes de repetir ({@link #operation}). A mesma chave
 * com outro conteúdo ou de outra pessoa é recusada. Encaminhamento que falhou aparece no pedido e é reprocessado sem
 * outro lançamento ({@link #retryForwarding}).
 */
@Service
public class Orders {

    private static final Logger log = LoggerFactory.getLogger(Orders.class);

    /**
     * Efeito durável gravado no commit do envio: a produção (H16/H18) recebe as linhas do pedido. A chave de
     * deduplicação é o id do pedido.
     */
    public static final String FORWARDING_JOB = "production.receber_pedido";

    /**
     * Linha enviada; valores em reais como texto com duas casas (especificação §7). {@code sectorId} vazio: dispensa
     * produção. {@code quantity} e {@code lineTotal}: o enviado; {@code activeQuantity} e {@code cancelledQuantity}:
     * as unidades que ainda contam (em todas as comandas) e as canceladas (H23, FR-20); {@code inTabQuantity}: as
     * ativas na comanda do pedido; {@code transferredQuantity}: as ativas que foram transferidas para outras comandas
     * (H25, FR-17).
     */
    public record SentLine(UUID id, int position, UUID productId, String productName, String unitPrice,
            int quantity, String lineTotal, String note, UUID sectorId, int activeQuantity, int cancelledQuantity,
            int inTabQuantity, int transferredQuantity) {
    }

    /** Pedido enviado. {@code forwarding}: encaminhamento à produção pendente, concluído ou na fila de falhas. */
    public record SentOrder(UUID id, UUID tabId, UUID operationKey, UUID sentBy, Instant sentAt, List<SentLine> lines,
            EffectState forwarding) {
    }

    /** Resultado do envio; {@code replayed}: a chave já tinha sido usada e este é o pedido gravado antes. */
    public record Sent(SentOrder order, boolean replayed) {
    }

    /** Linha que a produção recebe pelo encaminhamento. */
    public record ForwardedLine(UUID orderLineId, UUID sectorId, int quantity) {
    }

    public enum Refusal {
        COMANDA_NAO_ENCONTRADA,
        /** Comanda encerrada (ou em fechamento, H28): não recebe pedido; o rascunho fica no aparelho. */
        COMANDA_ENCERRADA,
        /** A chave de operação já serviu a outro pedido (outro conteúdo, outra comanda ou outra pessoa). */
        CHAVE_REUTILIZADA,
        PEDIDO_NAO_ENCONTRADO,
        /** Pediu reprocessar, mas o encaminhamento não está na fila de falhas. */
        ENCAMINHAMENTO_SEM_FALHA
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    /** O pedido não sai como está: esgotados e preços mudados, com o preço atual, para revisar o rascunho. */
    public record NeedsReview<T>(List<UUID> unavailable, List<OrderRules.PriceChange> priceChanges)
            implements Outcome<T> {
    }

    private final Memberships memberships;
    private final Tabs tabs;
    private final Catalog catalog;
    private final Jobs jobs;
    private final JdbcOrderStore store;
    private final Clock clock;

    private final Changes changes;

    Orders(Memberships memberships, Tabs tabs, Catalog catalog, Jobs jobs, JdbcOrderStore store, Clock clock,
            Changes changes) {
        this.changes = changes;
        this.memberships = memberships;
        this.tabs = tabs;
        this.catalog = catalog;
        this.jobs = jobs;
        this.store = store;
        this.clock = clock;
    }

    /** Envia o pedido inteiro ou nada (FR-15). */
    public Outcome<Sent> send(UUID actorId, UUID establishmentId, UUID tabId, UUID operationKey, List<RawLine> raw,
            String correlationId) {
        OrderRules.Checked<List<DraftLine>> checked = operationKey == null
                ? new OrderRules.Rejected<>(new OrderRules.Invalid("operationKey",
                        "Envio sem identificador de operação. Atualize a página e envie de novo."))
                : tabId == null
                        ? new OrderRules.Rejected<>(new OrderRules.Invalid("tabId", "Escolha a comanda do pedido."))
                        : OrderRules.lines(raw);
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (checked instanceof OrderRules.Rejected<List<DraftLine>> r) {
                return new Invalid<>(r.invalid().field(), r.invalid().message());
            }
            List<DraftLine> lines = ((OrderRules.Valid<List<DraftLine>>) checked).value();
            String fingerprint = OrderRules.fingerprint(tabId, lines);

            // A trava da comanda vem antes da chave: dois envios iguais simultâneos se enfileiram aqui e o segundo
            // encontra o pedido do primeiro.
            OrderTarget target = tabs.lockForOrderInCurrentUnit(tabId);
            Optional<OrderRow> previous = store.byOperationKey(operationKey);
            if (previous.isPresent()) {
                OrderRow p = previous.get();
                if (!p.sentBy().equals(actorId) || !p.tabId().equals(tabId) || !p.payloadHash().equals(fingerprint)) {
                    return new Refused<>(Refusal.CHAVE_REUTILIZADA);
                }
                log.info("Envio repetido do pedido {} (mesma operação) por {}", p.id(), actorId);
                return new Done<>(new Sent(view(List.of(p)).getFirst(), true));
            }
            switch (target) {
                case NAO_ENCONTRADA -> {
                    return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
                }
                case ENCERRADA -> {
                    return new Refused<>(Refusal.COMANDA_ENCERRADA);
                }
                case ABERTA -> {
                    // segue
                }
            }

            Set<UUID> productIds = new LinkedHashSet<>();
            lines.forEach(l -> productIds.add(l.productId()));
            Map<UUID, OrderableProduct> products = catalog.forOrderInCurrentUnit(productIds);
            Map<UUID, CurrentProduct> current = new LinkedHashMap<>();
            products.forEach((id, p) -> current.put(id, new CurrentProduct(id, p.price(), p.available())));
            Review review = OrderRules.review(lines, current);
            if (review.needed()) {
                return new NeedsReview<>(review.unavailable(), review.priceChanges());
            }

            Instant now = clock.instant();
            UUID orderId = UUID.randomUUID();
            store.insertOrder(orderId, tabId, operationKey, fingerprint, lines.size(), actorId, now, correlationId);
            List<LineRow> rows = new ArrayList<>(lines.size());
            int units = 0;
            for (int i = 0; i < lines.size(); i++) {
                DraftLine line = lines.get(i);
                OrderableProduct product = products.get(line.productId());
                rows.add(new LineRow(UUID.randomUUID(), orderId, i + 1, product.id(), product.name(),
                        OrderRules.money(product.price()), line.quantity(), line.note(), product.sectorId()));
                units += line.quantity();
            }
            store.insertLines(rows, tabId, now);
            jobs.enqueue(new JobRequest(FORWARDING_JOB, orderId.toString(), null, correlationId));
            store.audit("pedido.enviado", orderId, actorId, "comanda " + tabId + "; " + lines.size() + " linhas; "
                    + units + " unidades", correlationId);
            log.info("Pedido {} enviado na comanda {} por {} ({} linhas, {} unidades)", orderId, tabId, actorId,
                    lines.size(), units);
            changes.publishInCurrentTransaction(Topic.PEDIDO, tabId);
            return new Done<>(new Sent(view(List.of(store.order(orderId).orElseThrow())).getFirst(), false));
        });
    }

    /**
     * Situação de um envio pela chave de operação, para o aparelho conferir depois de timeout ou reconexão antes de
     * repetir (especificação §4). Só quem enviou encontra.
     */
    public Outcome<SentOrder> operation(UUID actorId, UUID establishmentId, UUID operationKey) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> store
                .byOperationKey(operationKey)
                .filter(o -> o.sentBy().equals(actorId))
                .<Outcome<SentOrder>>map(o -> new Done<>(view(List.of(o)).getFirst()))
                .orElseGet(() -> new Refused<>(Refusal.PEDIDO_NAO_ENCONTRADO)));
    }

    /** Pedidos enviados da comanda, do mais antigo ao mais novo, com o preço de cada envio (FR-16). */
    public Outcome<List<SentOrder>> ofTab(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (!tabs.existsInCurrentUnit(tabId)) {
                return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            return new Done<>(view(store.ofTab(tabId)));
        });
    }

    /** Comanda de origem de um item recebido por transferência (H25), como a equipe a identifica. */
    public record OriginTab(UUID id, int number, String name, String table) {
    }

    /**
     * Item de pedido de outra comanda com unidades ativas nesta (H25, FR-17): recebido por transferência. Preço do
     * envio; {@code quantity}: as unidades ativas aqui; {@code lineTotal}: preço × unidades aqui.
     */
    public record ReceivedLine(UUID id, String productName, String unitPrice, int quantity, String lineTotal,
            String note, UUID sectorId, Instant sentAt, OriginTab origin) {
    }

    /** Itens recebidos de outras comandas por transferência, dos mais antigos aos mais novos (H25). */
    public Outcome<List<ReceivedLine>> receivedOfTab(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (!tabs.existsInCurrentUnit(tabId)) {
                return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            List<JdbcOrderStore.ReceivedLineRow> rows = store.receivedLines(tabId);
            Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(
                    rows.stream().map(JdbcOrderStore.ReceivedLineRow::originTabId).distinct().toList());
            return new Done<>(rows.stream().map(r -> {
                Tabs.Label label = labels.get(r.originTabId());
                return new ReceivedLine(r.orderLineId(), r.productName(), OrderRules.money(r.unitPrice()).toPlainString(),
                        r.quantity(),
                        OrderRules.money(r.unitPrice().multiply(BigDecimal.valueOf(r.quantity()))).toPlainString(),
                        r.note(), r.sectorId(), r.sentAt(), label == null ? null
                                : new OriginTab(label.id(), label.number(), label.name(), label.table()));
            }).toList());
        });
    }

    /** Devolve à fila o encaminhamento que esgotou as tentativas, sem novo lançamento (FR-15). */
    public Outcome<SentOrder> retryForwarding(UUID actorId, UUID establishmentId, UUID orderId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            Optional<OrderRow> order = store.order(orderId);
            if (order.isEmpty()) {
                return new Refused<>(Refusal.PEDIDO_NAO_ENCONTRADO);
            }
            if (!jobs.retryExhaustedInCurrentTransaction(FORWARDING_JOB, orderId.toString(), actorId,
                    correlationId)) {
                return new Refused<>(Refusal.ENCAMINHAMENTO_SEM_FALHA);
            }
            store.audit("pedido.encaminhamento_retomado", orderId, actorId, null, correlationId);
            changes.publishInCurrentTransaction(Topic.PEDIDO, order.get().tabId());
            return new Done<>(view(List.of(order.get())).getFirst());
        });
    }

    /**
     * Linhas a encaminhar do pedido, na transação de unidade do job de encaminhamento (sem pessoa no contexto). Vazio
     * se o pedido não existe na unidade.
     */
    public List<ForwardedLine> forwardingInCurrentUnit(UUID orderId) {
        return store.lines(List.of(orderId)).stream()
                .map(l -> new ForwardedLine(l.id(), l.sectorId(), l.quantity())).toList();
    }

    /** Comanda do pedido, na transação de unidade (aviso de mudança da produção, H17). */
    public Optional<UUID> tabInCurrentUnit(UUID orderId) {
        return store.order(orderId).map(OrderRow::tabId);
    }

    /** Destino de uma linha: unidades alocadas numa comanda (especificação §3.2). */
    public record Allocation(UUID tabId, int quantity) {
    }

    /**
     * Linha enviada como a produção e a entrega a mostram (H18, FR-18): produto, quantidade, observação, envio e
     * destinos, sem valores.
     */
    public record ProductionDetail(UUID orderLineId, UUID orderId, int position, String productName, int quantity,
            String note,
            UUID sentBy, Instant sentAt, List<Allocation> allocations) {
    }

    /** Detalhes das linhas enviadas, na transação de unidade de quem chama (a permissão é conferida por ele). */
    public Map<UUID, ProductionDetail> forProductionInCurrentUnit(List<UUID> orderLineIds) {
        Map<UUID, List<Allocation>> allocations = new LinkedHashMap<>();
        for (JdbcOrderStore.AllocationRow a : store.allocations(orderLineIds)) {
            allocations.computeIfAbsent(a.orderLineId(), k -> new ArrayList<>())
                    .add(new Allocation(a.tabId(), a.quantity()));
        }
        Map<UUID, ProductionDetail> details = new LinkedHashMap<>();
        for (JdbcOrderStore.ProductionLineRow l : store.productionLines(orderLineIds)) {
            details.put(l.id(), new ProductionDetail(l.id(), l.orderId(), l.position(), l.productName(), l.quantity(), l.note(),
                    l.sentBy(), l.sentAt(), List.copyOf(allocations.getOrDefault(l.id(), List.of()))));
        }
        return details;
    }

    /** Item da comanda como o consumidor o aponta num problema (H21): produto e unidades nesta comanda, sem valores. */
    public record TabItem(UUID orderLineId, String productName, int quantity, Instant sentAt) {
    }

    /**
     * Itens enviados da comanda, dos mais antigos aos mais novos, na transação de unidade de quem chama (a comanda e o
     * acesso são conferidos por ele).
     */
    public List<TabItem> itemsOfTabInCurrentUnit(UUID tabId) {
        return store.tabItems(tabId).stream()
                .map(i -> new TabItem(i.orderLineId(), i.productName(), i.quantity(), i.sentAt())).toList();
    }

    /**
     * Lançamento de consumo da comanda (H22, C da conta): produto, unidades ativas nesta comanda, preço do envio e total
     * da linha (preço × unidades, 2 casas). Cancelamento (H23) e transferência (H25) mudam as unidades aqui.
     */
    public record ChargedLine(UUID orderLineId, String productName, int quantity, BigDecimal unitPrice,
            BigDecimal lineTotal, Instant sentAt) {
    }

    /**
     * Lançamentos ativos de consumo da comanda, dos mais antigos aos mais novos, na transação de unidade de quem chama
     * (a comanda, o acesso e a permissão são conferidos por ele).
     */
    public List<ChargedLine> chargedLinesOfTabInCurrentUnit(UUID tabId) {
        return store.chargedLines(tabId).stream()
                .filter(l -> l.quantity() > 0)
                .map(l -> new ChargedLine(l.orderLineId(), l.productName(), l.quantity(), OrderRules.money(l.unitPrice()),
                        OrderRules.money(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()))), l.sentAt()))
                .toList();
    }

    /** Linha enviada como o cancelamento a vê (H23): o enviado, as unidades ativas nesta comanda e o setor. */
    public record CancelTarget(UUID orderLineId, String productName, int quantity, int activeInTab, UUID sectorId) {
    }

    /**
     * A linha enviada com unidades alocadas na comanda, na transação de unidade de quem chama (a comanda já travada e a
     * permissão conferida por ele). Vazio se a linha não existe na unidade ou nunca esteve nesta comanda.
     */
    public Optional<CancelTarget> cancelTargetInCurrentUnit(UUID orderLineId, UUID tabId) {
        return store.cancelTarget(orderLineId, tabId)
                .map(r -> new CancelTarget(r.orderLineId(), r.productName(), r.quantity(), r.activeInTab(),
                        r.sectorId()));
    }

    /**
     * Retira unidades ativas da linha nesta comanda (H23, FR-20): as alocações diminuem, até zero, e o consumo da conta
     * (H22) acompanha. A linha enviada, o preço e o histórico ficam. Quem chama trava a comanda, confere a quantidade e
     * grava o fato do cancelamento.
     */
    public void cancelInCurrentUnit(UUID orderLineId, UUID tabId, int quantity) {
        take(orderLineId, tabId, quantity);
        changes.publishInCurrentTransaction(Topic.PEDIDO, tabId);
    }

    /** Tira unidades ativas da linha na comanda, das alocações mais recentes. */
    private void take(UUID orderLineId, UUID tabId, int quantity) {
        int left = quantity;
        for (JdbcOrderStore.AllocationIdRow a : store.allocationsInTab(orderLineId, tabId)) {
            if (left == 0) {
                break;
            }
            int take = Math.min(left, a.quantity());
            store.reduceAllocation(a.id(), a.quantity(), a.quantity() - take);
            left -= take;
        }
        if (left != 0) {
            // Quem chama conferiu as unidades ativas com a comanda travada: sobrar é erro de programa.
            throw new IllegalStateException("Retirada acima das unidades ativas na comanda.");
        }
    }

    /**
     * Passa unidades ativas da linha de uma comanda para outra (H25, FR-17, RN-01): a origem diminui (das alocações
     * mais recentes), o destino soma, e a soma das alocações da linha não muda. A linha enviada, o preço e a observação
     * ficam; a conta (H22) das duas acompanha. Quem chama trava as duas comandas, confere as unidades e grava o fato.
     */
    public void transferInCurrentUnit(UUID orderLineId, UUID fromTabId, UUID toTabId, int quantity) {
        take(orderLineId, fromTabId, quantity);
        store.addToAllocation(orderLineId, toTabId, quantity, clock.instant());
        changes.publishInCurrentTransaction(Topic.PEDIDO, fromTabId);
        changes.publishInCurrentTransaction(Topic.PEDIDO, toTabId);
    }

    private List<SentOrder> view(List<OrderRow> orders) {
        List<UUID> ids = orders.stream().map(OrderRow::id).toList();
        Map<UUID, List<SentLine>> lines = new LinkedHashMap<>();
        List<LineRow> rows = store.lines(ids);
        Map<UUID, UUID> tabOfOrder = new HashMap<>();
        orders.forEach(o -> tabOfOrder.put(o.id(), o.tabId()));
        Map<UUID, UUID> tabOfLine = new HashMap<>();
        rows.forEach(l -> tabOfLine.put(l.id(), tabOfOrder.get(l.orderId())));
        Map<UUID, Integer> active = new HashMap<>();
        Map<UUID, Integer> inTab = new HashMap<>();
        for (JdbcOrderStore.AllocationRow a : store.allocations(rows.stream().map(LineRow::id).toList())) {
            active.merge(a.orderLineId(), a.quantity(), Integer::sum);
            if (a.tabId().equals(tabOfLine.get(a.orderLineId()))) {
                inTab.merge(a.orderLineId(), a.quantity(), Integer::sum);
            }
        }
        for (LineRow l : rows) {
            int units = active.getOrDefault(l.id(), 0);
            int here = inTab.getOrDefault(l.id(), 0);
            lines.computeIfAbsent(l.orderId(), k -> new ArrayList<>()).add(new SentLine(l.id(), l.position(),
                    l.productId(), l.productName(), OrderRules.money(l.unitPrice()).toPlainString(), l.quantity(),
                    OrderRules.money(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()))).toPlainString(), l.note(),
                    l.sectorId(), units, l.quantity() - units, here, units - here));
        }
        Map<String, EffectState> forwarding = jobs.statesInCurrentTransaction(FORWARDING_JOB,
                ids.stream().map(UUID::toString).toList());
        return orders.stream().map(o -> new SentOrder(o.id(), o.tabId(), o.operationKey(), o.sentBy(), o.sentAt(),
                List.copyOf(lines.getOrDefault(o.id(), List.of())),
                forwarding.getOrDefault(o.id().toString(), EffectState.PENDENTE))).toList();
    }
}
