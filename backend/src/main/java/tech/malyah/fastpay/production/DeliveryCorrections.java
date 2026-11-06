package tech.malyah.fastpay.production;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.orders.Orders.ProductionDetail;
import tech.malyah.fastpay.production.domain.ProductionRules;
import tech.malyah.fastpay.production.domain.ProductionRules.Action;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;
import tech.malyah.fastpay.production.domain.ProductionRules.UndoBlock;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore.LineRow;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.OrderTarget;

/**
 * Correção de entrega (H24, FR-70, D-07), com {@link Permission#DESFAZER_ENTREGA} (gerente, administrador, garçom com
 * delegação) e motivo:
 * <ul>
 * <li><b>Desfazer</b>: a linha entregue volta a pronta quando todas as comandas com unidades ativas dela estão
 * abertas. Numa transação, as comandas são travadas em ordem de id (a mesma trava do envio, do cancelamento, da conta e
 * do fechamento: um fechamento ao mesmo tempo espera ou faz esta esperar) e depois a linha; a versão lida precisa ser a
 * atual (mudança de alocação ou de quantidade a incrementa). O histórico ganha {@code ENTREGA_DESFEITA}, apontando para
 * a entrega cuja amostra de tempo deixa de valer; nada é apagado. A linha reaparece nas entregas com aviso novo de item
 * pronto (H20), e a nova entrega válida redefine o tempo.</li>
 * <li><b>Ocorrência</b>: com alguma dessas comandas encerrada (paga), a entrega não se desfaz e nada é reaberto nem
 * devolvido; registra-se {@code OCORRENCIA_ENTREGA} no histórico da linha, com motivo, para o gerente tratar pelo
 * caminho próprio (devolução, H35).</li>
 * </ul>
 * Contrato para H28/H30: "em fechamento" e cobrança existente também impedem desfazer (e levam à ocorrência quando
 * paga), conferidos aqui depois de travar as comandas.
 */
@Service
public class DeliveryCorrections {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCorrections.class);

    public enum Refusal {
        LINHA_NAO_ENCONTRADA,
        VERSAO_DESATUALIZADA,
        /** A linha não está entregue. */
        ITEM_NAO_ENTREGUE,
        /** Todas as unidades foram canceladas depois da entrega. */
        ITEM_CANCELADO,
        /** Alguma comanda de destino está encerrada: cabe a ocorrência, não desfazer. */
        COMANDA_ENCERRADA,
        /** Todas as comandas de destino estão abertas: cabe desfazer, não a ocorrência. */
        ENTREGA_PODE_SER_DESFEITA
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    /** Ocorrência registrada: a linha como está (sem mudança) e o passo do histórico. */
    public record Occurrence(UUID eventId, Production.Line line) {
    }

    private final Memberships memberships;
    private final Orders orders;
    private final Tabs tabs;
    private final Production production;
    private final JdbcProductionStore store;
    private final Changes changes;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    DeliveryCorrections(Memberships memberships, Orders orders, Tabs tabs, Production production,
            JdbcProductionStore store, Changes changes, Clock clock, ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.orders = orders;
        this.tabs = tabs;
        this.production = production;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
        this.events = events;
    }

    /** Volta a linha entregue para pronta, com motivo (FR-70). */
    public Outcome<Production.Line> undo(UUID actorId, UUID establishmentId, UUID lineId, Long version,
            String reason, String correlationId) {
        String r = ProductionRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.DESFAZER_ENTREGA, me -> {
            Invalid<Production.Line> invalid = validate(version, r);
            if (invalid != null) {
                return invalid;
            }
            Locked locked = lock(lineId);
            if (locked.refusal() != null) {
                return new Refused<>(locked.refusal());
            }
            LineRow line = locked.line();
            if (line.version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            UndoBlock block = ProductionRules.undoDelivery(line.status(), line.remaining(), locked.anyClosed());
            if (block != null) {
                return new Refused<>(refusal(block));
            }
            UUID delivery = store.lastDelivery(line.id())
                    .orElseThrow(() -> new IllegalStateException("Linha entregue sem o passo da entrega."));
            Instant now = clock.instant();
            store.undoDelivery(line.id(), version, now);
            UUID eventId = UUID.randomUUID();
            store.event(eventId, line.id(), Action.ENTREGA_DESFEITA, Status.ENTREGUE, Status.PRONTO, r, version + 1,
                    delivery, actorId, now, correlationId);
            store.audit("entrega.desfeita", line.id(), actorId, "linha " + line.orderLineId() + "; comandas "
                    + locked.tabIds() + "; entrega " + delivery + " invalidada", correlationId);
            Production.Line after = production.linesInCurrentUnit(List.of(line.id())).get(line.id());
            List<UUID> tabIds = List.copyOf(locked.tabIds());
            tabIds.forEach(tab -> changes.publishInCurrentTransaction(Topic.PRODUCAO, tab));
            // Aviso de item pronto (H20) no mesmo commit: a linha precisa ser entregue de novo.
            events.publishEvent(new ProductionLineChanged(line.id(), ProductionLineChanged.Change.ENTREGA_DESFEITA,
                    tabIds, actorId, now));
            log.info("Entrega desfeita da linha {} (comandas {}), entrega {} invalidada, por {}", line.id(),
                    locked.tabIds(), delivery, actorId);
            return new Done<>(after);
        });
    }

    /**
     * Registra a ocorrência de uma entrega errada quando alguma comanda de destino já foi encerrada (FR-70): a linha, a
     * conta e o pagamento ficam como estão, sem devolução automática.
     */
    public Outcome<Occurrence> occurrence(UUID actorId, UUID establishmentId, UUID lineId, Long version,
            String reason, String correlationId) {
        String r = ProductionRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.DESFAZER_ENTREGA, me -> {
            Invalid<Occurrence> invalid = validate(version, r);
            if (invalid != null) {
                return invalid;
            }
            Locked locked = lock(lineId);
            if (locked.refusal() != null) {
                return new Refused<>(locked.refusal());
            }
            LineRow line = locked.line();
            if (line.version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            UndoBlock block = ProductionRules.undoDelivery(line.status(), line.remaining(), locked.anyClosed());
            if (block != UndoBlock.COMANDA_ENCERRADA) {
                return new Refused<>(block == null ? Refusal.ENTREGA_PODE_SER_DESFEITA : refusal(block));
            }
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            // A linha não muda: o passo grava a versão atual.
            store.event(eventId, line.id(), Action.OCORRENCIA_ENTREGA, Status.ENTREGUE, Status.ENTREGUE, r,
                    line.version(), null, actorId, now, correlationId);
            store.audit("entrega.ocorrencia", line.id(), actorId, "linha " + line.orderLineId() + "; comandas "
                    + locked.tabIds(), correlationId);
            log.info("Ocorrência de entrega na linha {} (comandas {}) por {}", line.id(), locked.tabIds(), actorId);
            return new Done<>(new Occurrence(eventId,
                    production.linesInCurrentUnit(List.of(line.id())).get(line.id())));
        });
    }

    private static <T> Invalid<T> validate(Long version, String reason) {
        if (version == null) {
            return new Invalid<>("version", "Atualize a tela e tente de novo.");
        }
        if (reason == null) {
            return new Invalid<>("reason", "Informe o motivo, com %d a %d caracteres."
                    .formatted(ProductionRules.REASON_MIN, ProductionRules.REASON_MAX));
        }
        return null;
    }

    /** Linha travada, as comandas com unidades ativas dela (travadas, em ordem de id) e se alguma está encerrada. */
    private record Locked(LineRow line, TreeSet<UUID> tabIds, boolean anyClosed, Refusal refusal) {
    }

    /**
     * Trava as comandas com unidades ativas da linha e depois a linha, na mesma ordem do cancelamento (comanda, depois
     * linha), para não haver espera cruzada. Quem muda as alocações trava as comandas e incrementa a versão da linha:
     * uma mudança entre a leitura e a trava aparece como versão diferente.
     */
    private Locked lock(UUID lineId) {
        LineRow seen = store.line(lineId).orElse(null);
        if (seen == null) {
            return new Locked(null, null, false, Refusal.LINHA_NAO_ENCONTRADA);
        }
        ProductionDetail detail = orders.forProductionInCurrentUnit(List.of(seen.orderLineId()))
                .get(seen.orderLineId());
        if (detail == null) {
            return new Locked(null, null, false, Refusal.LINHA_NAO_ENCONTRADA);
        }
        TreeSet<UUID> tabIds = new TreeSet<>();
        detail.allocations().stream().filter(a -> a.quantity() > 0).forEach(a -> tabIds.add(a.tabId()));
        boolean anyClosed = false;
        for (UUID tabId : tabIds) {
            OrderTarget target = tabs.lockForOrderInCurrentUnit(tabId);
            if (target == OrderTarget.NAO_ENCONTRADA) {
                return new Locked(null, null, false, Refusal.LINHA_NAO_ENCONTRADA);
            }
            anyClosed |= target == OrderTarget.ENCERRADA;
        }
        LineRow line = store.lock(lineId).orElse(null);
        if (line == null) {
            return new Locked(null, null, false, Refusal.LINHA_NAO_ENCONTRADA);
        }
        return new Locked(line, tabIds, anyClosed, null);
    }

    private static Refusal refusal(UndoBlock block) {
        return switch (block) {
            case NAO_ENTREGUE -> Refusal.ITEM_NAO_ENTREGUE;
            case SEM_UNIDADES_ATIVAS -> Refusal.ITEM_CANCELADO;
            case COMANDA_ENCERRADA -> Refusal.COMANDA_ENCERRADA;
        };
    }
}
