package tech.malyah.fastpay.production;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.orders.Orders.CancelTarget;
import tech.malyah.fastpay.production.domain.ProductionRules;
import tech.malyah.fastpay.production.domain.ProductionRules.Action;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore.LineRow;
import tech.malyah.fastpay.production.infrastructure.JdbcTransferStore;
import tech.malyah.fastpay.production.infrastructure.JdbcTransferStore.TransferRow;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.OrderTarget;

/**
 * Transferência de unidades entre comandas (H25, FR-17, RN-01, CA-10, D-05).
 * <ul>
 * <li>Quem tem {@link Permission#TRANSFERIR_UNIDADES} (gerente, administrador, garçom com delegação) passa unidades
 * inteiras de uma linha de uma comanda ativa para outra ativa da mesma unidade, em qualquer etapa, com motivo. Uma
 * unidade nunca é dividida em valor.</li>
 * <li>Numa transação só: as duas comandas são travadas em ordem de id (a mesma trava do envio, do cancelamento, da conta
 * e do fechamento), depois a linha de produção. As alocações mudam (a conta das duas, H22, muda no mesmo commit); a
 * linha enviada, o preço, a observação, a produção e as etapas ficam; a versão da linha sobe (a entrega com os
 * destinos de antes é recusada, H18); o histórico da produção ganha o passo e o fato da transferência fica gravado.
 * Qualquer falha desfaz tudo.</li>
 * <li>Problema do consumidor em aberto (H21): do item, bloqueia transferir aquele item; da conta, bloqueia entradas e
 * saídas da comanda ({@link TransferGuard}). Desconto que passar da autoridade depois da mudança fica em revisão na
 * conta (FR-68) e bloqueia a cobrança, não a transferência.</li>
 * <li>A tela manda as unidades que viu na comanda de origem: se mudaram (outra correção ao mesmo tempo), é recusada
 * e a tela relê.</li>
 * </ul>
 */
@Service
public class Transfers {

    private static final Logger log = LoggerFactory.getLogger(Transfers.class);

    /** Resultado: unidades da linha que ficaram na origem e as que o destino tem agora. */
    public record Transferred(UUID transferId, UUID orderLineId, String productName, UUID fromTabId, UUID toTabId,
            int quantity, int remainingInFrom, int quantityInTo, Status status, long version) {
    }

    public enum Refusal {
        COMANDA_NAO_ENCONTRADA,
        /** Comanda de origem encerrada: não se transfere. */
        COMANDA_ENCERRADA,
        DESTINO_NAO_ENCONTRADO,
        /** Comanda de destino encerrada. */
        DESTINO_ENCERRADO,
        ITEM_NAO_ENCONTRADO,
        /** A produção ainda não recebeu a linha (encaminhamento pendente ou com falha). */
        ITEM_AGUARDANDO_PRODUCAO,
        /** Nenhuma unidade ativa do item na comanda de origem. */
        ITEM_SEM_UNIDADES,
        /** As unidades na origem mudaram desde que a tela foi carregada. */
        QUANTIDADE_DESATUALIZADA,
        /** Problema em aberto do item, na origem ou no destino. */
        PROBLEMA_NO_ITEM,
        /** Problema em aberto da conta da comanda de origem. */
        PROBLEMA_NA_CONTA,
        /** Problema em aberto da conta da comanda de destino. */
        PROBLEMA_NA_CONTA_DESTINO
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    private final Memberships memberships;
    private final Orders orders;
    private final Tabs tabs;
    private final Production production;
    private final JdbcProductionStore lines;
    private final JdbcTransferStore store;
    private final TransferGuard guard;
    private final Changes changes;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Transfers(Memberships memberships, Orders orders, Tabs tabs, Production production, JdbcProductionStore lines,
            JdbcTransferStore store, TransferGuard guard, Changes changes, Clock clock,
            ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.orders = orders;
        this.tabs = tabs;
        this.production = production;
        this.lines = lines;
        this.store = store;
        this.guard = guard;
        this.changes = changes;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Transfere {@code quantity} unidades da linha da comanda {@code fromTabId} para {@code toTabId}.
     * {@code expectedQuantity}: as unidades ativas que a tela mostrava na origem.
     */
    public Outcome<Transferred> transfer(UUID actorId, UUID establishmentId, UUID fromTabId, UUID orderLineId,
            UUID toTabId, Integer quantity, Integer expectedQuantity, String reason, String correlationId) {
        String r = ProductionRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.TRANSFERIR_UNIDADES, me -> {
            if (toTabId == null) {
                return new Invalid<>("toTabId", "Escolha a comanda de destino.");
            }
            if (toTabId.equals(fromTabId)) {
                return new Invalid<>("toTabId", "Escolha outra comanda: o destino é a própria comanda de origem.");
            }
            if (quantity == null || quantity < 1 || quantity > 99) {
                return new Invalid<>("quantity", "Informe quantas unidades transferir, a partir de 1.");
            }
            if (expectedQuantity == null) {
                return new Invalid<>("expectedQuantity", "Atualize a tela e tente de novo.");
            }
            if (r == null) {
                return new Invalid<>("reason", "Informe o motivo, com %d a %d caracteres."
                        .formatted(ProductionRules.REASON_MIN, ProductionRules.REASON_MAX));
            }
            // Trava em ordem de id: duas transferências cruzadas (A→B e B→A) não esperam uma pela outra.
            OrderTarget from = null;
            OrderTarget to = null;
            for (UUID tab : fromTabId.compareTo(toTabId) < 0 ? List.of(fromTabId, toTabId)
                    : List.of(toTabId, fromTabId)) {
                OrderTarget target = tabs.lockForOrderInCurrentUnit(tab);
                if (tab.equals(fromTabId)) {
                    from = target;
                } else {
                    to = target;
                }
            }
            if (from == OrderTarget.NAO_ENCONTRADA) {
                return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            if (to == OrderTarget.NAO_ENCONTRADA) {
                return new Refused<>(Refusal.DESTINO_NAO_ENCONTRADO);
            }
            if (from == OrderTarget.ENCERRADA) {
                return new Refused<>(Refusal.COMANDA_ENCERRADA);
            }
            if (to == OrderTarget.ENCERRADA) {
                return new Refused<>(Refusal.DESTINO_ENCERRADO);
            }
            CancelTarget item = orders.cancelTargetInCurrentUnit(orderLineId, fromTabId).orElse(null);
            if (item == null) {
                return new Refused<>(Refusal.ITEM_NAO_ENCONTRADO);
            }
            LineRow line = lines.lockByOrderLine(orderLineId).orElse(null);
            if (line == null) {
                return new Refused<>(Refusal.ITEM_AGUARDANDO_PRODUCAO);
            }
            if (item.activeInTab() != expectedQuantity) {
                return new Refused<>(Refusal.QUANTIDADE_DESATUALIZADA);
            }
            if (item.activeInTab() == 0) {
                return new Refused<>(Refusal.ITEM_SEM_UNIDADES);
            }
            if (quantity > item.activeInTab()) {
                return new Invalid<>("quantity",
                        "Esta comanda tem %d unidade(s) ativa(s) deste item: transfira no máximo %d."
                                .formatted(item.activeInTab(), item.activeInTab()));
            }
            Optional<Refusal> blocked = blocked(fromTabId, toTabId, orderLineId);
            if (blocked.isPresent()) {
                return new Refused<>(blocked.get());
            }

            Instant now = clock.instant();
            orders.transferInCurrentUnit(orderLineId, fromTabId, toTabId, quantity);
            lines.transfer(line.id(), line.version());
            lines.event(line.id(), Action.TRANSFERIDO, line.status(), line.status(), r, line.version() + 1, actorId,
                    now, correlationId);
            UUID id = UUID.randomUUID();
            int remaining = item.activeInTab() - quantity;
            store.insert(new TransferRow(id, orderLineId, line.id(), fromTabId, toTabId, quantity, remaining,
                    line.status(), r, actorId, now), correlationId);
            store.audit("item.transferido", id, actorId, "linha " + orderLineId + "; comanda " + fromTabId + " > "
                    + toTabId + "; " + quantity + " de " + item.activeInTab() + " unidade(s); etapa " + line.status(),
                    correlationId);

            // Mesma transação: produção, pedidos e as duas contas relêem depois do commit.
            Production.Line after = production.linesInCurrentUnit(List.of(line.id())).get(line.id());
            List<UUID> destinations = after.destinations().stream().map(Production.Destination::tabId).distinct()
                    .toList();
            int inTo = after.destinations().stream().filter(d -> d.tabId().equals(toTabId))
                    .mapToInt(Production.Destination::quantity).sum();
            Set<UUID> touched = new LinkedHashSet<>(destinations);
            touched.add(fromTabId);
            touched.add(toTabId);
            changes.publishInCurrentTransaction(Topic.CONTA, fromTabId);
            changes.publishInCurrentTransaction(Topic.CONTA, toTabId);
            touched.forEach(tab -> changes.publishInCurrentTransaction(Topic.PRODUCAO, tab));
            // Avisos do atendimento (H20) seguem as unidades no mesmo commit.
            events.publishEvent(new ProductionLineChanged(line.id(), ProductionLineChanged.Change.TRANSFERIDA,
                    destinations, actorId, now));
            log.info("Transferência {}: {} unidade(s) da linha {} da comanda {} para {} ({}), por {}", id, quantity,
                    orderLineId, fromTabId, toTabId, line.status(), actorId);
            return new Done<>(new Transferred(id, orderLineId, item.productName(), fromTabId, toTabId, quantity,
                    remaining, inTo, line.status(), after.version()));
        });
    }

    private Optional<Refusal> blocked(UUID fromTabId, UUID toTabId, UUID orderLineId) {
        Optional<TransferGuard.Block> from = guard.blockInCurrentUnit(fromTabId, orderLineId);
        if (from.isPresent()) {
            return Optional.of(from.get() == TransferGuard.Block.PROBLEMA_NO_ITEM ? Refusal.PROBLEMA_NO_ITEM
                    : Refusal.PROBLEMA_NA_CONTA);
        }
        return guard.blockInCurrentUnit(toTabId, orderLineId).map(b -> b == TransferGuard.Block.PROBLEMA_NO_ITEM
                ? Refusal.PROBLEMA_NO_ITEM
                : Refusal.PROBLEMA_NA_CONTA_DESTINO);
    }
}
