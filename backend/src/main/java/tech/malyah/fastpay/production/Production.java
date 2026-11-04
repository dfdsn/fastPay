package tech.malyah.fastpay.production;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.catalog.Catalog;
import tech.malyah.fastpay.catalog.Catalog.SectorSettings;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.orders.Orders.ProductionDetail;
import tech.malyah.fastpay.production.domain.ProductionRules;
import tech.malyah.fastpay.production.domain.ProductionRules.Action;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore.LineRow;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;

/**
 * Fila de produção e entrega por linha (H18, FR-18–FR-22, CA-08, D-06). Cada linha enviada tem uma sequência só, para
 * toda a quantidade ({@link ProductionRules}).
 * <ul>
 * <li>Produção ({@link Permission#OPERAR_PRODUCAO}): vê a fila (todos os setores ou um), inicia, marca pronto e
 * sinaliza "não é possível atender" com motivo, ou retira a sinalização;</li>
 * <li>quem atende ({@link Permission#OPERAR_COMANDA}): vê o que está pronto (com preparo e sem preparo, em classes
 * separadas) e as impossibilidades abertas, e confirma a entrega da linha inteira a todos os destinos.</li>
 * </ul>
 * Toda mudança exige a versão lida da linha: duas pessoas não avançam a mesma etapa duas vezes, e a entrega com os
 * destinos de antes de uma transferência (H25) ou cancelamento (H23), que incrementam a versão, é recusada. Cada
 * mudança grava o histórico ({@code production_event}: quem, quando, de onde para onde, motivo) e avisa as telas das
 * comandas da linha (H17). A produção nunca vê valores.
 */
@Service
public class Production {

    private static final Logger log = LoggerFactory.getLogger(Production.class);

    /** Destino da linha: unidades numa comanda, identificada por número, nome e mesa. */
    public record Destination(UUID tabId, int number, String name, String table, int quantity) {
    }

    /**
     * Linha como as telas mostram. {@code sectorId} vazio: sem preparo. {@code sentAt}: envio do pedido (tempo total);
     * {@code statusSince}: início da etapa atual. {@code delayLimitMinutes} vazio: sem limite (sem setor).
     * {@code responsible}: quem abriu a comanda do primeiro destino. Sem valores.
     */
    public record Line(UUID id, UUID orderId, UUID sectorId, String sectorName, Integer delayLimitMinutes,
            String productName, int quantity, String note, Status status, Instant sentAt, Instant statusSince,
            Instant startedAt, Instant readyAt, Instant deliveredAt, boolean late, Impossibility impossible,
            String responsible, List<Destination> destinations, long version) {
    }

    public record Impossibility(String reason, Instant at) {
    }

    /** Linhas e o instante do servidor, para a tela contar os tempos sem depender do relógio do aparelho. */
    public record Board(Instant now, List<Line> lines) {
    }

    public enum Refusal {
        LINHA_NAO_ENCONTRADA,
        VERSAO_DESATUALIZADA,
        ETAPA_INVALIDA,
        LINHA_IMPOSSIVEL
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
    private final Catalog catalog;
    private final JdbcProductionStore store;
    private final Changes changes;
    private final Clock clock;

    Production(Memberships memberships, Orders orders, Tabs tabs, Catalog catalog,
            JdbcProductionStore store, Changes changes, Clock clock) {
        this.memberships = memberships;
        this.orders = orders;
        this.tabs = tabs;
        this.catalog = catalog;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    /** Fila da produção (aguardando e em preparo), de um setor ou de todos, das mais antigas às mais novas. */
    public Board queue(UUID actorId, UUID establishmentId, UUID sectorId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO,
                me -> board(store.queue(sectorId)));
    }

    /** Prontas para entregar (com e sem preparo) e linhas com impossibilidade aberta. */
    public Board deliveries(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA,
                me -> board(store.deliveries()));
    }

    public Outcome<Line> start(UUID actorId, UUID establishmentId, UUID lineId, Long version, String correlationId) {
        return change(actorId, establishmentId, Permission.OPERAR_PRODUCAO, lineId, version, Action.INICIADO, null,
                correlationId);
    }

    public Outcome<Line> ready(UUID actorId, UUID establishmentId, UUID lineId, Long version, String correlationId) {
        return change(actorId, establishmentId, Permission.OPERAR_PRODUCAO, lineId, version, Action.PRONTO, null,
                correlationId);
    }

    /** Sinaliza "não é possível atender" (FR-21): a linha não avança até retirar a sinalização ou cancelar (H23). */
    public Outcome<Line> impossible(UUID actorId, UUID establishmentId, UUID lineId, Long version, String reason,
            String correlationId) {
        String r = ProductionRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO, me -> r == null
                ? new Invalid<>("reason", "Informe o motivo, com %d a %d caracteres."
                        .formatted(ProductionRules.REASON_MIN, ProductionRules.REASON_MAX))
                : apply(actorId, lineId, version, Action.IMPOSSIVEL, r, correlationId));
    }

    public Outcome<Line> withdrawImpossible(UUID actorId, UUID establishmentId, UUID lineId, Long version,
            String correlationId) {
        return change(actorId, establishmentId, Permission.OPERAR_PRODUCAO, lineId, version,
                Action.IMPOSSIVEL_RETIRADO, null, correlationId);
    }

    /** Entrega a linha inteira a todos os destinos mostrados (FR-19, D-06): sem entrega parcial. */
    public Outcome<Line> deliver(UUID actorId, UUID establishmentId, UUID lineId, Long version,
            String correlationId) {
        return change(actorId, establishmentId, Permission.OPERAR_COMANDA, lineId, version, Action.ENTREGUE, null,
                correlationId);
    }

    private Outcome<Line> change(UUID actorId, UUID establishmentId, Permission permission, UUID lineId, Long version,
            Action action, String reason, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, permission,
                me -> apply(actorId, lineId, version, action, reason, correlationId));
    }

    private Outcome<Line> apply(UUID actorId, UUID lineId, Long version, Action action, String reason,
            String correlationId) {
        if (version == null) {
            return new Invalid<>("version", "Atualize a tela e tente de novo.");
        }
        LineRow current = store.lock(lineId).orElse(null);
        if (current == null) {
            return new Refused<>(Refusal.LINHA_NAO_ENCONTRADA);
        }
        if (current.version() != version) {
            return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
        }
        ProductionRules.Decision decision = ProductionRules.decide(
                new ProductionRules.Line(current.status(), current.sectorId() != null, current.impossible()), action);
        if (decision instanceof ProductionRules.Refused r) {
            return new Refused<>(switch (r.refusal()) {
                case ETAPA_INVALIDA -> Refusal.ETAPA_INVALIDA;
                case LINHA_IMPOSSIVEL -> Refusal.LINHA_IMPOSSIVEL;
            });
        }
        Status to = ((ProductionRules.Next) decision).status();
        Instant now = clock.instant();
        switch (action) {
            case INICIADO, PRONTO, ENTREGUE -> store.advance(lineId, version, to, now);
            case IMPOSSIVEL -> store.markImpossible(lineId, version, reason, actorId, now);
            case IMPOSSIVEL_RETIRADO -> store.clearImpossible(lineId, version);
        }
        store.event(lineId, action, current.status(), to, reason, version + 1, actorId, now, correlationId);
        Line line = board(List.of(store.line(lineId).orElseThrow())).lines().getFirst();
        // Mesma transação: as telas das comandas da linha relêem depois do commit.
        line.destinations().stream().map(Destination::tabId).distinct()
                .forEach(tab -> changes.publishInCurrentTransaction(Topic.PRODUCAO, tab));
        log.info("Linha de produção {}: {} ({} > {}) por {}", lineId, action, current.status(), to, actorId);
        return new Done<>(line);
    }

    private Board board(List<LineRow> rows) {
        Instant now = clock.instant();
        List<UUID> orderLineIds = rows.stream().map(LineRow::orderLineId).toList();
        Map<UUID, ProductionDetail> details = orders.forProductionInCurrentUnit(orderLineIds);
        Set<UUID> tabIds = new LinkedHashSet<>();
        details.values().forEach(d -> d.allocations().forEach(a -> tabIds.add(a.tabId())));
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(List.copyOf(tabIds));
        Map<UUID, String> names = memberships.namesInCurrentUnit(
                labels.values().stream().map(Tabs.Label::openedBy).distinct().toList());
        Map<UUID, SectorSettings> sectors = catalog.sectorsInCurrentUnit(
                rows.stream().map(LineRow::sectorId).filter(Objects::nonNull).distinct().toList());

        List<Line> lines = new ArrayList<>(rows.size());
        Map<UUID, Integer> positions = new HashMap<>();
        for (LineRow row : rows) {
            ProductionDetail detail = details.get(row.orderLineId());
            if (detail == null) {
                // Linha de pedido sem leitura nesta unidade: não acontece com RLS e FK; nunca mostra dado parcial.
                continue;
            }
            List<Destination> destinations = new ArrayList<>();
            for (Orders.Allocation a : detail.allocations()) {
                Tabs.Label label = labels.get(a.tabId());
                if (label != null) {
                    destinations.add(new Destination(label.id(), label.number(), label.name(), label.table(),
                            a.quantity()));
                }
            }
            positions.put(row.id(), detail.position());
            SectorSettings sector = row.sectorId() == null ? null : sectors.get(row.sectorId());
            Integer limit = sector == null ? null : sector.delayLimitMinutes();
            String responsible = destinations.isEmpty() ? null
                    : names.get(labels.get(destinations.getFirst().tabId()).openedBy());
            lines.add(new Line(row.id(), detail.orderId(), row.sectorId(), sector == null ? null : sector.name(), limit,
                    detail.productName(), row.quantity(), detail.note(), row.status(), detail.sentAt(),
                    row.statusSince(), row.startedAt(), row.readyAt(), row.deliveredAt(),
                    ProductionRules.late(row.status(), detail.sentAt(), limit, now),
                    row.impossible() ? new Impossibility(row.impossibleReason(), row.impossibleAt()) : null,
                    responsible, List.copyOf(destinations), row.version()));
        }
        // Das mais antigas às mais novas; no mesmo pedido, na ordem das linhas.
        lines.sort(Comparator.comparing(Line::sentAt).thenComparing(Line::orderId)
                .thenComparingInt(l -> positions.get(l.id())));
        return new Board(now, List.copyOf(lines));
    }
}
