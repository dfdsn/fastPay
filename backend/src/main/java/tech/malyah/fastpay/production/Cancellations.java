package tech.malyah.fastpay.production;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import tech.malyah.fastpay.production.domain.ProductionRules.CancelPlan;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;
import tech.malyah.fastpay.production.infrastructure.JdbcCancellationStore;
import tech.malyah.fastpay.production.infrastructure.JdbcCancellationStore.CancellationRow;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore.LineRow;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.OrderTarget;

/**
 * Cancelamento de quantidade e ciência da produção (H23, FR-20, FR-21, CA-09, D-06).
 * <ul>
 * <li>Quem tem {@link Permission#CANCELAR_ITEM} (gerente, administrador, garçom com delegação) cancela unidades
 * inteiras de uma linha numa comanda ativa, em qualquer etapa, com motivo. Numa transação só, com a comanda travada (a
 * mesma trava do envio, da conta e do fechamento) e depois a linha de produção: as alocações diminuem (a conta, H22,
 * muda no mesmo commit), a linha de produção soma as canceladas e incrementa a versão (a entrega com os destinos de
 * antes é recusada, H18), o histórico da produção ganha o passo e o fato do cancelamento fica gravado com a etapa em
 * que a linha estava. Nada é apagado.</li>
 * <li>Aguardando preparo: a fila só diminui. Em preparo ou pronto (com setor): a produção precisa dar ciência
 * ({@link Permission#OPERAR_PRODUCAO}), sem que a correção espere por ela. Entregue: só a correção financeira, a entrega
 * continua no histórico. Cancelamento total antes da entrega encerra a linha ({@link Status#CANCELADA}) e resolve a
 * sinalização de impossibilidade e os avisos do atendimento.</li>
 * <li>A tela manda as unidades ativas que viu na comanda: se mudaram (outra correção ao mesmo tempo), a segunda é
 * recusada e a tela relê. Comanda encerrada (paga) é recusada: o caminho é a devolução, sem apagar fatos.</li>
 * <li>Cancelar não conclui problema do consumidor (FR-20): quem atende conclui pelo fluxo da H21.</li>
 * </ul>
 */
@Service
public class Cancellations {

    private static final Logger log = LoggerFactory.getLogger(Cancellations.class);

    /** Resultado do cancelamento: unidades da linha agora, e se a produção precisa dar ciência. */
    public record Cancelled(UUID cancellationId, UUID orderLineId, UUID tabId, String productName, int quantity,
            int originalQuantity, int cancelledQuantity, int remainingInTab, Status stage, Status status,
            boolean requiresAck) {
    }

    /** Comanda como a produção a identifica num aviso de cancelamento. */
    public record TabLabel(UUID id, int number, String name, String table) {
    }

    /**
     * Cancelamento como a produção o vê para dar ciência: o que foi cancelado, em que etapa, o que resta da linha, o
     * motivo, quem e quando. Sem valores.
     */
    public record Notice(UUID id, UUID productionLineId, String productName, UUID sectorId, String sectorName,
            Status stage, Status status, int quantity, int originalQuantity, int cancelledQuantity, int remaining,
            String reason, String cancelledBy, Instant cancelledAt, TabLabel tab, String acknowledgedBy,
            Instant acknowledgedAt) {
    }

    public record Notices(Instant now, List<Notice> cancellations) {
    }

    /** Ciência gravada; {@code alreadyAcknowledged}: alguém já tinha dado ciência e nada mudou. */
    public record Acknowledged(Notice cancellation, boolean alreadyAcknowledged) {
    }

    public enum Refusal {
        COMANDA_NAO_ENCONTRADA,
        /** Comanda encerrada ou paga: não se cancela; o caminho é a devolução. */
        COMANDA_ENCERRADA,
        ITEM_NAO_ENCONTRADO,
        /** A produção ainda não recebeu a linha (encaminhamento pendente ou com falha). */
        ITEM_AGUARDANDO_PRODUCAO,
        /** Nenhuma unidade ativa nesta comanda. */
        ITEM_JA_CANCELADO,
        /** As unidades ativas mudaram desde que a tela foi carregada. */
        QUANTIDADE_DESATUALIZADA,
        CANCELAMENTO_NAO_ENCONTRADO,
        /** Cancelamento que não pediu ciência (aguardando preparo, entregue ou sem preparo). */
        CIENCIA_NAO_EXIGIDA
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
    private final JdbcCancellationStore store;
    private final Changes changes;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Cancellations(Memberships memberships, Orders orders, Tabs tabs, Production production,
            JdbcProductionStore lines, JdbcCancellationStore store, Changes changes, Clock clock,
            ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.orders = orders;
        this.tabs = tabs;
        this.production = production;
        this.lines = lines;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Cancela {@code quantity} unidades da linha na comanda. {@code expectedQuantity}: as unidades ativas que a tela
     * mostrava nesta comanda.
     */
    public Outcome<Cancelled> cancel(UUID actorId, UUID establishmentId, UUID tabId, UUID orderLineId,
            Integer quantity, Integer expectedQuantity, String reason, String correlationId) {
        String r = ProductionRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.CANCELAR_ITEM, me -> {
            if (quantity == null || quantity < 1 || quantity > 99) {
                return new Invalid<>("quantity", "Informe quantas unidades cancelar, a partir de 1.");
            }
            if (expectedQuantity == null) {
                return new Invalid<>("expectedQuantity", "Atualize a tela e tente de novo.");
            }
            if (r == null) {
                return new Invalid<>("reason", "Informe o motivo, com %d a %d caracteres."
                        .formatted(ProductionRules.REASON_MIN, ProductionRules.REASON_MAX));
            }
            OrderTarget target = tabs.lockForOrderInCurrentUnit(tabId);
            if (target == OrderTarget.NAO_ENCONTRADA) {
                return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            if (target == OrderTarget.ENCERRADA) {
                return new Refused<>(Refusal.COMANDA_ENCERRADA);
            }
            CancelTarget item = orders.cancelTargetInCurrentUnit(orderLineId, tabId).orElse(null);
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
                return new Refused<>(Refusal.ITEM_JA_CANCELADO);
            }
            if (quantity > item.activeInTab()) {
                return new Invalid<>("quantity", "Esta comanda tem %d unidade(s) ativa(s) deste item: cancele no máximo %d."
                        .formatted(item.activeInTab(), item.activeInTab()));
            }
            Instant now = clock.instant();
            CancelPlan plan = ProductionRules.cancel(
                    new ProductionRules.Line(line.status(), line.sectorId() != null, line.impossible()),
                    line.quantity(), line.cancelledQuantity(), quantity);

            orders.cancelInCurrentUnit(orderLineId, tabId, quantity);
            lines.cancel(line.id(), line.version(), plan.cancelledAfter(), plan.to(), plan.clearsImpossible(), now);
            lines.event(line.id(), Action.CANCELADO, line.status(), plan.to(), r, line.version() + 1, actorId, now,
                    correlationId);
            UUID id = UUID.randomUUID();
            int remainingInTab = item.activeInTab() - quantity;
            store.insert(new CancellationRow(id, orderLineId, line.id(), tabId, quantity, remainingInTab,
                    line.status(), r, actorId, now, plan.requiresAck(), null, null), correlationId);
            store.audit("item.cancelado", id, actorId, "linha " + orderLineId + "; comanda " + tabId + "; "
                    + quantity + " de " + item.activeInTab() + " unidade(s); etapa " + line.status(), correlationId);

            // Mesma transação: pedido (Orders), conta e produção relêem depois do commit.
            Set<UUID> tabIds = new LinkedHashSet<>(production.linesInCurrentUnit(List.of(line.id())).values().stream()
                    .flatMap(l -> l.destinations().stream().map(Production.Destination::tabId)).toList());
            tabIds.add(tabId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            tabIds.forEach(tab -> changes.publishInCurrentTransaction(Topic.PRODUCAO, tab));
            if (plan.to() == Status.CANCELADA) {
                // Avisos do atendimento (H20) da linha terminam no mesmo commit.
                events.publishEvent(new ProductionLineChanged(line.id(), ProductionLineChanged.Change.CANCELADA,
                        List.copyOf(tabIds), actorId, now));
            }
            log.info("Cancelamento {}: {} unidade(s) da linha {} na comanda {} ({} > {}), ciência {}, por {}", id,
                    quantity, orderLineId, tabId, line.status(), plan.to(), plan.requiresAck(), actorId);
            return new Done<>(new Cancelled(id, orderLineId, tabId, item.productName(),
                    line.quantity() - plan.cancelledAfter(), line.quantity(), plan.cancelledAfter(), remainingInTab,
                    line.status(), plan.to(), plan.requiresAck()));
        });
    }

    /** Cancelamentos que esperam a ciência da produção, de um setor ou de todos, dos mais antigos aos mais novos. */
    public Notices pending(UUID actorId, UUID establishmentId, UUID sectorId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO, me -> {
            List<Notice> notices = notices(store.pending()).stream()
                    .filter(n -> sectorId == null || sectorId.equals(n.sectorId())).toList();
            return new Notices(clock.instant(), notices);
        });
    }

    /** Ciência da produção. Repetir (ou outra pessoa já ter dado) não muda nada e responde o que está gravado. */
    public Outcome<Acknowledged> acknowledge(UUID actorId, UUID establishmentId, UUID cancellationId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO, me -> {
            CancellationRow row = store.find(cancellationId).orElse(null);
            if (row == null) {
                return new Refused<>(Refusal.CANCELAMENTO_NAO_ENCONTRADO);
            }
            if (!row.requiresAck()) {
                return new Refused<>(Refusal.CIENCIA_NAO_EXIGIDA);
            }
            boolean now = store.acknowledge(cancellationId, actorId, clock.instant());
            CancellationRow current = store.find(cancellationId).orElseThrow();
            if (now) {
                store.audit("item.cancelamento_ciente", cancellationId, actorId, "linha " + row.orderLineId(),
                        correlationId);
                changes.publishInCurrentTransaction(Topic.PRODUCAO, row.tabId());
                log.info("Ciência do cancelamento {} por {}", cancellationId, actorId);
            }
            return new Done<>(new Acknowledged(notices(List.of(current)).getFirst(), !now));
        });
    }

    private List<Notice> notices(List<CancellationRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Production.Line> byLine = production.linesInCurrentUnit(
                rows.stream().map(CancellationRow::productionLineId).distinct().toList());
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(
                rows.stream().map(CancellationRow::tabId).distinct().toList());
        List<UUID> people = new ArrayList<>();
        rows.forEach(c -> {
            people.add(c.cancelledBy());
            if (c.acknowledgedBy() != null) {
                people.add(c.acknowledgedBy());
            }
        });
        Map<UUID, String> names = memberships.namesInCurrentUnit(people.stream().distinct().toList());
        List<Notice> notices = new ArrayList<>(rows.size());
        for (CancellationRow c : rows) {
            Production.Line line = byLine.get(c.productionLineId());
            Tabs.Label label = labels.get(c.tabId());
            if (line == null || label == null) {
                // Sem leitura nesta unidade: não acontece com RLS e FK; nunca mostra dado parcial.
                continue;
            }
            notices.add(new Notice(c.id(), c.productionLineId(), line.productName(), line.sectorId(),
                    line.sectorName(), c.stage(), line.status(), c.quantity(), line.originalQuantity(),
                    line.cancelledQuantity(), line.quantity(), c.reason(), names.get(c.cancelledBy()),
                    c.cancelledAt(), new TabLabel(label.id(), label.number(), label.name(), label.table()),
                    c.acknowledgedBy() == null ? null : names.get(c.acknowledgedBy()), c.acknowledgedAt()));
        }
        return List.copyOf(notices);
    }
}
