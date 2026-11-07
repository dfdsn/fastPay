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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.catalog.Catalog;
import tech.malyah.fastpay.catalog.Catalog.SectorSettings;
import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.PermissionDeniedException;
import tech.malyah.fastpay.identity.UnitAccessDeniedException;
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

    /**
     * Destino da linha: unidades numa comanda, identificada por número, nome e mesa. {@code closed}: a comanda já foi
     * encerrada (H24: a entrega dela não se desfaz, registra-se ocorrência).
     */
    public record Destination(UUID tabId, int number, String name, String table, int quantity, boolean closed) {
    }

    /**
     * Linha como as telas mostram. {@code sectorId} vazio: sem preparo. {@code sentAt}: envio do pedido (tempo total);
     * {@code statusSince}: início da etapa atual. {@code delayLimitMinutes} vazio: sem limite (sem setor).
     * {@code responsible}: o garçom responsável da comanda do primeiro destino, ou "Central" se ela é atendida pela
     * central (H20). {@code quantity}: as unidades que ainda contam; {@code originalQuantity} e
     * {@code cancelledQuantity}: o enviado e o cancelado (H23, FR-20). Sem valores.
     */
    public record Line(UUID id, UUID orderId, UUID sectorId, String sectorName, Integer delayLimitMinutes,
            String productName, int quantity, int originalQuantity, int cancelledQuantity, String note, Status status, Instant sentAt, Instant statusSince,
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
    private final ApplicationEventPublisher events;

    Production(Memberships memberships, Orders orders, Tabs tabs, Catalog catalog,
            JdbcProductionStore store, Changes changes, Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.memberships = memberships;
        this.orders = orders;
        this.tabs = tabs;
        this.catalog = catalog;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * Em que ponto está um item que ainda impede o fechamento (H28, FR-31): {@code AGUARDANDO_PRODUCAO} quando o
     * encaminhamento ainda não chegou à produção; {@code IMPOSSIVEL} com a sinalização aberta.
     */
    public enum PendingStage {
        AGUARDANDO_PRODUCAO, AGUARDANDO_PREPARO, EM_PREPARO, PRONTO, IMPOSSIVEL
    }

    /** Unidades ativas de um item na comanda que ainda não foram entregues. */
    public record PendingItem(UUID orderLineId, String productName, int quantity, PendingStage stage) {
    }

    /**
     * Itens da comanda que impedem o fechamento (H28, FR-31, CA-08): unidades ativas não entregues nem canceladas, na
     * transação de unidade de quem chama (que confere a permissão ou o acesso). Vazia quando tudo foi entregue ou
     * cancelado. Lida com a comanda travada, ela não muda até o fim da transação: envio, cancelamento, transferência e
     * desfazer entrega travam a mesma comanda.
     */
    public List<PendingItem> pendingOfTabInCurrentUnit(UUID tabId) {
        return store.pendingOfTab(tabId).stream().map(r -> new PendingItem(r.orderLineId(), r.productName(),
                r.quantity(), r.status() == null ? PendingStage.AGUARDANDO_PRODUCAO
                        : r.impossible() ? PendingStage.IMPOSSIVEL : PendingStage.valueOf(r.status().name())))
                .toList();
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

    /**
     * Entregues mais recentes (H24, FR-70), para quem pode desfazer a entrega ({@link Permission#DESFAZER_ENTREGA}):
     * cada linha traz os destinos e se a comanda de cada um já foi encerrada.
     */
    public Board delivered(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.DESFAZER_ENTREGA,
                me -> {
                    Board board = board(store.delivered());
                    // Das entregas mais recentes às mais antigas.
                    List<Line> lines = new ArrayList<>(board.lines());
                    lines.sort(Comparator.comparing(Line::deliveredAt).reversed().thenComparing(Line::id));
                    return new Board(board.now(), List.copyOf(lines));
                });
    }

    /**
     * Linhas pelo id, na transação de unidade de quem chama, que confere a permissão (H20: o atendimento mostra o
     * item de cada chamado). Linha de outra unidade não aparece.
     */
    public Map<UUID, Line> linesInCurrentUnit(List<UUID> lineIds) {
        Map<UUID, Line> lines = new HashMap<>();
        board(store.lines(lineIds)).lines().forEach(l -> lines.put(l.id(), l));
        return lines;
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

    /** Ação de um lote (H19): as etapas da produção. Entrega e impossibilidade continuam uma linha por vez. */
    public enum BatchAction {
        INICIAR(Action.INICIADO),
        PRONTO(Action.PRONTO);

        private final Action action;

        BatchAction(Action action) {
            this.action = action;
        }
    }

    /** Linha pedida no lote, com a versão que a tela leu. */
    public record BatchItem(UUID lineId, Long version) {
    }

    public enum BatchStatus {
        /** A etapa avançou agora. */
        CONCLUIDA,
        /** A mesma ação já tinha sido feita sobre essa versão (lote repetido ou outra pessoa): nada mudou. */
        IGNORADA,
        /** Não avançou; {@code code} diz por quê. As demais linhas seguem. */
        FALHA
    }

    /** Resultado de uma linha, na ordem do pedido. {@code code}: a recusa (FALHA) ou {@code JA_APLICADA}. */
    public record BatchLine(UUID lineId, BatchStatus status, String code) {
    }

    public record BatchResult(int concluded, int ignored, int failed, List<BatchLine> lines) {
    }

    /** Código da linha que já tinha recebido a mesma ação sobre a versão informada. */
    public static final String JA_APLICADA = "JA_APLICADA";
    /** A permissão foi retirada durante o lote. */
    public static final String SEM_PERMISSAO = "PERMISSAO_NEGADA";
    /** O vínculo com a unidade foi desativado durante o lote. */
    public static final String SEM_ACESSO = "UNIDADE_NAO_AUTORIZADA";
    /** Erro inesperado numa linha: registrado no log; as concluídas antes dela continuam valendo. */
    public static final String FALHA_INESPERADA = "FALHA_INESPERADA";

    /**
     * Lote de produção (H19, FR-19, FR-69, D-04, D-13): até {@link ProductionRules#BATCH_MAX} linhas distintas, cada
     * uma com a versão lida. Cada linha roda na sua própria transação, com o vínculo e a permissão conferidos de novo:
     * uma recusa (outra pessoa avançou, linha impossível, etapa errada, de outra unidade) não desfaz as que passaram.
     * Repetir o mesmo lote não avança uma segunda etapa: a linha que já recebeu essa ação sobre a versão informada
     * volta como {@link BatchStatus#IGNORADA}.
     *
     * @throws tech.malyah.fastpay.identity.UnitAccessDeniedException sem vínculo ativo na unidade
     * @throws tech.malyah.fastpay.identity.PermissionDeniedException sem a permissão da produção
     */
    public Outcome<BatchResult> batch(UUID actorId, UUID establishmentId, BatchAction action, List<BatchItem> items,
            String correlationId) {
        // O lote inteiro só é recusado de saída quando a pessoa não opera a produção nesta unidade, ou o pedido é
        // inválido; dali em diante, cada linha tem o próprio resultado.
        Invalid<BatchResult> invalid = memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO,
                me -> validate(action, items));
        if (invalid != null) {
            return invalid;
        }
        List<BatchLine> results = new ArrayList<>(items.size());
        for (BatchItem item : items) {
            results.add(batchLine(actorId, establishmentId, action.action, item, correlationId));
        }
        int concluded = (int) results.stream().filter(r -> r.status() == BatchStatus.CONCLUIDA).count();
        int ignored = (int) results.stream().filter(r -> r.status() == BatchStatus.IGNORADA).count();
        log.info("Lote de produção {} ({}): {} concluídas, {} ignoradas, {} falhas, por {}", correlationId, action,
                concluded, ignored, results.size() - concluded - ignored, actorId);
        return new Done<>(new BatchResult(concluded, ignored, results.size() - concluded - ignored,
                List.copyOf(results)));
    }

    private static Invalid<BatchResult> validate(BatchAction action, List<BatchItem> items) {
        if (action == null) {
            return new Invalid<>("action", "Escolha a ação do lote: iniciar ou marcar pronto.");
        }
        if (items == null || items.isEmpty()) {
            return new Invalid<>("lines", "Escolha ao menos um item.");
        }
        if (items.size() > ProductionRules.BATCH_MAX) {
            return new Invalid<>("lines", "Escolha no máximo %d itens por vez.".formatted(ProductionRules.BATCH_MAX));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (BatchItem item : items) {
            if (item == null || item.lineId() == null || item.version() == null) {
                return new Invalid<>("lines", "Atualize a tela e tente de novo.");
            }
            if (!ids.add(item.lineId())) {
                return new Invalid<>("lines", "O mesmo item aparece duas vezes no lote. Atualize a tela.");
            }
        }
        return null;
    }

    /** Uma linha do lote, na sua transação; nada que aconteça aqui desfaz as linhas anteriores. */
    private BatchLine batchLine(UUID actorId, UUID establishmentId, Action action, BatchItem item,
            String correlationId) {
        UUID lineId = item.lineId();
        try {
            return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_PRODUCAO, me -> {
                Outcome<Line> outcome = apply(actorId, lineId, item.version(), action, null, correlationId);
                return switch (outcome) {
                    case Done<Line> done -> new BatchLine(lineId, BatchStatus.CONCLUIDA, null);
                    case Refused<Line> r when r.refusal() == Refusal.VERSAO_DESATUALIZADA
                            && store.applied(lineId, action, item.version()) ->
                        new BatchLine(lineId, BatchStatus.IGNORADA, JA_APLICADA);
                    case Refused<Line> r -> new BatchLine(lineId, BatchStatus.FALHA, r.refusal().name());
                    case Invalid<Line> i -> new BatchLine(lineId, BatchStatus.FALHA, "DADOS_INVALIDOS");
                };
            });
        } catch (PermissionDeniedException e) {
            return new BatchLine(lineId, BatchStatus.FALHA, SEM_PERMISSAO);
        } catch (UnitAccessDeniedException e) {
            return new BatchLine(lineId, BatchStatus.FALHA, SEM_ACESSO);
        } catch (RuntimeException e) {
            log.error("Lote de produção {}: falha inesperada na linha {}", correlationId, lineId, e);
            return new BatchLine(lineId, BatchStatus.FALHA, FALHA_INESPERADA);
        }
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
            case CANCELADO -> throw new IllegalStateException("Cancelamento passa por Cancellations.");
            case ENTREGA_DESFEITA, OCORRENCIA_ENTREGA ->
                throw new IllegalStateException("Correção de entrega passa por DeliveryCorrections.");
            case TRANSFERIDO -> throw new IllegalStateException("Transferência passa por Transfers.");
        }
        store.event(lineId, action, current.status(), to, reason, version + 1, actorId, now, correlationId);
        Line line = board(List.of(store.line(lineId).orElseThrow())).lines().getFirst();
        // Mesma transação: as telas das comandas da linha relêem depois do commit.
        List<UUID> tabIds = line.destinations().stream().map(Destination::tabId).distinct().toList();
        tabIds.forEach(tab -> changes.publishInCurrentTransaction(Topic.PRODUCAO, tab));
        ProductionLineChanged.Change change = switch (action) {
            case PRONTO -> ProductionLineChanged.Change.PRONTA;
            case ENTREGUE -> ProductionLineChanged.Change.ENTREGUE;
            case IMPOSSIVEL -> ProductionLineChanged.Change.IMPOSSIVEL;
            case IMPOSSIVEL_RETIRADO -> ProductionLineChanged.Change.IMPOSSIVEL_RETIRADO;
            case INICIADO -> null;
            case CANCELADO -> throw new IllegalStateException("Cancelamento passa por Cancellations.");
            case ENTREGA_DESFEITA, OCORRENCIA_ENTREGA ->
                throw new IllegalStateException("Correção de entrega passa por DeliveryCorrections.");
            case TRANSFERIDO -> throw new IllegalStateException("Transferência passa por Transfers.");
        };
        if (change != null) {
            // Avisos do atendimento (H20) no mesmo commit da mudança.
            events.publishEvent(new ProductionLineChanged(lineId, change, tabIds, actorId, now));
        }
        log.info("Linha de produção {}: {} ({} > {}) por {}", lineId, action, current.status(), to, actorId);
        return new Done<>(line);
    }

    /** Quem responde pela comanda: o garçom responsável, ou a central (H20). */
    private static String responsible(Tabs.Label label, Map<UUID, String> names) {
        if (label.attendanceMode() == AttendanceMode.CENTRAL) {
            return "Central";
        }
        return label.responsibleId() == null ? null : names.get(label.responsibleId());
    }

    private Board board(List<LineRow> rows) {
        Instant now = clock.instant();
        List<UUID> orderLineIds = rows.stream().map(LineRow::orderLineId).toList();
        Map<UUID, ProductionDetail> details = orders.forProductionInCurrentUnit(orderLineIds);
        Set<UUID> tabIds = new LinkedHashSet<>();
        details.values().forEach(d -> d.allocations().forEach(a -> tabIds.add(a.tabId())));
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(List.copyOf(tabIds));
        Map<UUID, String> names = memberships.namesInCurrentUnit(labels.values().stream()
                .map(Tabs.Label::responsibleId).filter(Objects::nonNull).distinct().toList());
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
                // Alocação zerada por cancelamento (H23) não é destino.
                if (label != null && a.quantity() > 0) {
                    destinations.add(new Destination(label.id(), label.number(), label.name(), label.table(),
                            a.quantity(), label.closed()));
                }
            }
            positions.put(row.id(), detail.position());
            SectorSettings sector = row.sectorId() == null ? null : sectors.get(row.sectorId());
            Integer limit = sector == null ? null : sector.delayLimitMinutes();
            String responsible = destinations.isEmpty() ? null
                    : responsible(labels.get(destinations.getFirst().tabId()), names);
            lines.add(new Line(row.id(), detail.orderId(), row.sectorId(), sector == null ? null : sector.name(), limit,
                    detail.productName(), row.remaining(), row.quantity(), row.cancelledQuantity(), detail.note(), row.status(), detail.sentAt(),
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
