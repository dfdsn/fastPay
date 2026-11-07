package tech.malyah.fastpay.attendance;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.attendance.Attendance.Done;
import tech.malyah.fastpay.attendance.Attendance.Invalid;
import tech.malyah.fastpay.attendance.Attendance.Outcome;
import tech.malyah.fastpay.attendance.Attendance.Owner;
import tech.malyah.fastpay.attendance.Attendance.Refusal;
import tech.malyah.fastpay.attendance.Attendance.Refused;
import tech.malyah.fastpay.attendance.Attendance.TabRef;
import tech.malyah.fastpay.attendance.domain.IssueRules;
import tech.malyah.fastpay.attendance.domain.IssueRules.Correction;
import tech.malyah.fastpay.attendance.domain.IssueRules.Status;
import tech.malyah.fastpay.attendance.infrastructure.JdbcIssueStore;
import tech.malyah.fastpay.attendance.infrastructure.JdbcIssueStore.IssueRow;
import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.GuestAccess;
import tech.malyah.fastpay.tabs.Tabs;

/**
 * Problemas do consumidor (H21, FR-24, FR-25, CA-14, UJ-03). O cliente, com o acesso da própria comanda, aponta um
 * item ou a conta e descreve o motivo; o problema entra na fila de atendimento com a modalidade da comanda (H20).
 * Quem atende ({@link Permission#ATENDER_PROBLEMA}) começa o atendimento — exclusivo na central — e explica ou faz a
 * correção pelo caminho próprio (cancelamento, desconto, transferência), reabrindo quando preciso; conclui como tratado
 * quem tem {@link Permission#CONCLUIR_PROBLEMA}, com resolução escrita e a declaração da correção. Marcar tratado não
 * altera valores, e corrigir não conclui o problema. Quem não pode concluir ou precisa de autorização que não tem
 * encaminha ao gerente. Problema aberto bloqueia nova cobrança ({@link #blocksChargeInCurrentUnit}); a cobrança já
 * existente é verificada pelas histórias de pagamento, nunca cancelada aqui. Depois do encerramento, o problema não
 * desfaz a quitação: nasce encaminhado ao gerente, e só quem autoriza devolução o conclui.
 */
@Service
public class ConsumerIssues {

    private static final Logger log = LoggerFactory.getLogger(ConsumerIssues.class);

    /** Item apontado pelo cliente; nulo quando o problema é da conta. */
    public record Item(UUID orderLineId, String productName) {
    }

    /**
     * Problema como a equipe o vê. {@code owner}: o garçom responsável da comanda ou, na central, quem atende; nulo na
     * central sem ninguém. {@code attendedBy}: quem começou o atendimento. {@code forwarded}: encaminhado ao gerente.
     */
    public record Issue(UUID id, Status status, AttendanceMode mode, TabRef tab, Item item, boolean aboutCouvert,
            String description,
            boolean afterClosure, Owner owner, boolean mine, Owner attendedBy, boolean forwarded, Instant createdAt,
            Instant attendedAt, Instant forwardedAt, long version) {
    }

    /** Problema como o cliente o vê: sem nomes da equipe. {@code aboutCouvert}: contestação do couvert (H26). */
    public record GuestIssue(UUID id, Item item, boolean aboutCouvert, String description, Status status, boolean afterClosure,
            Instant createdAt, Instant attendedAt, Instant concludedAt, String resolution) {
    }

    /** Itens da comanda que o cliente pode apontar e os problemas dela. */
    public record GuestIssues(Instant now, boolean closed, List<Orders.TabItem> items, List<GuestIssue> issues) {
    }

    private final GuestAccess guests;
    private final Memberships memberships;
    private final Tabs tabs;
    private final Orders orders;
    private final JdbcIssueStore store;
    private final Changes changes;
    private final Clock clock;

    ConsumerIssues(GuestAccess guests, Memberships memberships, Tabs tabs, Orders orders, JdbcIssueStore store,
            Changes changes, Clock clock) {
        this.guests = guests;
        this.memberships = memberships;
        this.tabs = tabs;
        this.orders = orders;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    // Consumidor

    /** Itens e problemas da comanda para o cliente com acesso válido; vazio sem acesso (outra comanda, vencido). */
    public Optional<GuestIssues> ofGuestTab(List<String> grantTokens, UUID tabId) {
        return guests.inTab(grantTokens, tabId, context -> {
            List<Orders.TabItem> items = orders.itemsOfTabInCurrentUnit(tabId);
            return new GuestIssues(clock.instant(), context.closed(), items,
                    store.ofTab(tabId).stream().map(r -> guestIssue(r, items)).toList());
        });
    }

    /**
     * O cliente informa um problema do item (da própria comanda) ou da conta ({@code orderLineId} nulo). Um em aberto
     * por item e um da conta: repetir recebe {@link Refusal#PROBLEMA_JA_ABERTO}. Vazio sem acesso válido.
     * {@code aboutCouvert}: a contestação do couvert (H26, FR-29) é um problema da conta marcado; quem tem
     * {@code CANCELAR_ITEM} cancela o couvert com motivo, e cancelar não conclui o problema.
     */
    public Optional<Outcome<GuestIssue>> report(List<String> grantTokens, UUID tabId, UUID orderLineId,
            boolean aboutCouvert, String description, String correlationId) {
        return guests.inTab(grantTokens, tabId, context -> {
            if (aboutCouvert && orderLineId != null) {
                return new Invalid<>("orderLineId", "A contestação do couvert não aponta um item.");
            }
            Optional<String> text = IssueRules.text(description);
            if (text.isEmpty()) {
                return new Invalid<>("description", "Conte o que aconteceu em 5 a 300 caracteres.");
            }
            List<Orders.TabItem> items = orders.itemsOfTabInCurrentUnit(tabId);
            if (orderLineId != null && items.stream().noneMatch(i -> i.orderLineId().equals(orderLineId))) {
                return new Refused<>(Refusal.ITEM_NAO_ENCONTRADO, null);
            }
            Tabs.Label label = tabs.labelsInCurrentUnit(List.of(tabId)).get(tabId);
            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            if (!store.insert(id, tabId, orderLineId, aboutCouvert, text.get(), label.attendanceMode(), context.closed(),
                    context.grantId(), now)) {
                return new Refused<>(Refusal.PROBLEMA_JA_ABERTO, null);
            }
            if (context.closed()) {
                // FR-11: problema aberto depois do encerramento suspende a expiração do acesso avulso (H29).
                tabs.refreshAccessAfterClosureInCurrentUnit(tabId, true);
            }
            store.audit("problema.informado", id, null,
                    aboutCouvert ? "couvert" : orderLineId == null ? "conta" : "item " + orderLineId,
                    correlationId);
            changes.publishInCurrentTransaction(Topic.PROBLEMA, tabId);
            log.info("Problema {} informado na comanda {} ({})", id, tabId,
                    context.closed() ? "após o encerramento" : "em atendimento");
            return new Done<>(guestIssue(store.issue(id).orElseThrow(), items));
        });
    }

    // Equipe

    /** Problemas ainda não tratados da unidade, para a fila de atendimento (permissão conferida por quem chama). */
    List<Issue> pendingInCurrentUnit(UUID actorId) {
        return issues(store.pending(), actorId);
    }

    /**
     * A comanda tem problema ainda não tratado: nova cobrança fica bloqueada (FR-24, CA-14). Para as histórias de
     * fechamento e pagamento (H28, H30), na transação de unidade delas, depois de travar a comanda. Uma cobrança já
     * existente não é cancelada por isso: é verificada.
     */
    public boolean blocksChargeInCurrentUnit(UUID tabId) {
        return store.anyPending(tabId);
    }

    /** Pessoas ativas da unidade que atendem problemas, para o gerente escolher na reatribuição. */
    public List<tech.malyah.fastpay.identity.IdentitySummary> attendants(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.REATRIBUIR_ATENDIMENTO,
                me -> memberships.holdersInCurrentUnit(Permission.ATENDER_PROBLEMA));
    }

    /** Começa o atendimento: exclusivo na central; no garçom responsável, quem chega depois ajuda. */
    public Outcome<Issue> attend(UUID actorId, UUID establishmentId, UUID issueId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.ATENDER_PROBLEMA, me -> {
            Optional<IssueRow> row = store.lock(issueId);
            if (row.isEmpty()) {
                return notFound();
            }
            return decide(actorId, row.get(), IssueRules.attend(rule(row.get()), actorId), null, null,
                    correlationId);
        });
    }

    /** Reatribui o problema da central (gerente) a alguém ativo que atende problemas, com a versão lida. */
    public Outcome<Issue> reassign(UUID actorId, UUID establishmentId, UUID issueId, Long version, UUID assigneeId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.REATRIBUIR_ATENDIMENTO, me -> {
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            if (assigneeId == null) {
                return new Invalid<>("assigneeId", "Escolha para quem passar o problema.");
            }
            Optional<IssueRow> row = store.lock(issueId);
            if (row.isEmpty()) {
                return notFound();
            }
            IssueRules.Decision decision = IssueRules.reassign(rule(row.get()), version, assigneeId);
            if (decision instanceof IssueRules.Attend
                    && !memberships.holdsInCurrentUnit(assigneeId, Permission.ATENDER_PROBLEMA)) {
                return new Invalid<>("assigneeId", "Escolha alguém ativo da equipe que atende problemas.");
            }
            return decide(actorId, row.get(), decision, null, null, correlationId);
        });
    }

    /** Encaminha ao gerente: a correção exige autorização que quem atende não tem, ou ele não pode concluir. */
    public Outcome<Issue> forward(UUID actorId, UUID establishmentId, UUID issueId, Long version,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.ATENDER_PROBLEMA, me -> {
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            Optional<IssueRow> row = store.lock(issueId);
            if (row.isEmpty()) {
                return notFound();
            }
            return decide(actorId, row.get(), IssueRules.forward(rule(row.get()), version), null, null,
                    correlationId);
        });
    }

    /**
     * Conclui como tratado, com resolução (5–300) e a declaração da correção. Não altera valores; a correção é feita
     * antes, pelo caminho próprio. Problema que chegou após o encerramento: só quem autoriza devolução.
     */
    public Outcome<Issue> conclude(UUID actorId, UUID establishmentId, UUID issueId, Long version, String resolution,
            Correction correction, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.CONCLUIR_PROBLEMA, me -> {
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            Optional<String> text = IssueRules.text(resolution);
            if (text.isEmpty()) {
                return new Invalid<>("resolution", "Descreva a resolução em 5 a 300 caracteres.");
            }
            if (correction == null) {
                return new Invalid<>("correction", "Diga se houve correção e se ela já foi feita.");
            }
            Optional<IssueRow> row = store.lock(issueId);
            if (row.isEmpty()) {
                return notFound();
            }
            IssueRules.Decision decision = IssueRules.conclude(rule(row.get()), version,
                    me.has(Permission.AUTORIZAR_DEVOLUCAO));
            return decide(actorId, row.get(), decision, text.get(), correction, correlationId);
        });
    }

    private Outcome<Issue> decide(UUID actorId, IssueRow row, IssueRules.Decision decision, String resolution,
            Correction correction, String correlationId) {
        Instant now = clock.instant();
        switch (decision) {
            case IssueRules.Refused r -> {
                return new Refused<>(switch (r.refusal()) {
                    case TRATADO -> Refusal.PROBLEMA_TRATADO;
                    case JA_ASSUMIDO -> Refusal.PROBLEMA_JA_ASSUMIDO;
                    case COM_RESPONSAVEL -> Refusal.PROBLEMA_COM_RESPONSAVEL;
                    case NAO_ATENDIDO -> Refusal.PROBLEMA_NAO_ATENDIDO;
                    case EXIGE_GERENTE -> Refusal.PROBLEMA_EXIGE_GERENTE;
                    case VERSAO_DESATUALIZADA -> Refusal.VERSAO_DESATUALIZADA;
                }, r.refusal() == IssueRules.Refusal.JA_ASSUMIDO ? name(row.attendedBy()) : null);
            }
            case IssueRules.Unchanged u -> {
                return new Done<>(single(row, actorId));
            }
            case IssueRules.Attend a -> {
                store.attend(row.id(), row.version(), a.attendant(), now);
                store.audit(row.attendedBy() == null ? "problema.em_atendimento" : "problema.reatribuido", row.id(),
                        actorId, (row.attendedBy() == null ? "" : "de " + row.attendedBy() + " ") + "para "
                                + a.attendant(), correlationId);
            }
            case IssueRules.Forward f -> {
                store.forward(row.id(), row.version(), actorId, now);
                store.audit("problema.encaminhado", row.id(), actorId, null, correlationId);
            }
            case IssueRules.Conclude c -> {
                store.conclude(row.id(), row.version(), actorId, resolution, correction, now);
                store.audit("problema.tratado", row.id(), actorId, correction.name(), correlationId);
                // FR-11: na comanda encerrada, a última resolução dá mais 7 dias de acesso avulso (H29).
                tabs.refreshAccessAfterClosureInCurrentUnit(row.tabId(), store.anyPending(row.tabId()));
            }
        }
        changes.publishInCurrentTransaction(Topic.PROBLEMA, row.tabId());
        log.info("Problema {} mudou ({}) por {}", row.id(), decision.getClass().getSimpleName(), actorId);
        return new Done<>(single(store.issue(row.id()).orElseThrow(), actorId));
    }

    private static Outcome<Issue> notFound() {
        return new Refused<>(Refusal.PROBLEMA_NAO_ENCONTRADO, null);
    }

    private String name(UUID identityId) {
        return identityId == null ? null : memberships.namesInCurrentUnit(List.of(identityId)).get(identityId);
    }

    private static IssueRules.Issue rule(IssueRow row) {
        return new IssueRules.Issue(row.mode(), row.status(), row.attendedBy(), row.forwardedAt() != null,
                row.afterClosure(), row.version());
    }

    private Issue single(IssueRow row, UUID actorId) {
        return issues(List.of(row), actorId).getFirst();
    }

    private List<Issue> issues(List<IssueRow> rows, UUID actorId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(rows.stream().map(IssueRow::tabId).distinct().toList());
        Map<UUID, Orders.ProductionDetail> lines = orders.forProductionInCurrentUnit(
                rows.stream().map(IssueRow::orderLineId).filter(Objects::nonNull).distinct().toList());
        Set<UUID> people = new LinkedHashSet<>();
        for (IssueRow row : rows) {
            UUID owner = owner(row, labels.get(row.tabId()));
            if (owner != null) {
                people.add(owner);
            }
            if (row.attendedBy() != null) {
                people.add(row.attendedBy());
            }
        }
        Map<UUID, String> names = memberships.namesInCurrentUnit(List.copyOf(people));
        List<Issue> issues = new ArrayList<>(rows.size());
        for (IssueRow row : rows) {
            Tabs.Label label = labels.get(row.tabId());
            if (label == null) {
                continue;
            }
            UUID ownerId = owner(row, label);
            Orders.ProductionDetail line = row.orderLineId() == null ? null : lines.get(row.orderLineId());
            issues.add(new Issue(row.id(), row.status(), row.mode(),
                    new TabRef(label.id(), label.number(), label.name(), label.table()),
                    row.orderLineId() == null ? null
                            : new Item(row.orderLineId(), line == null ? null : line.productName()),
                    row.aboutCouvert(), row.description(), row.afterClosure(),
                    ownerId == null ? null : new Owner(ownerId, names.get(ownerId)), Objects.equals(ownerId, actorId),
                    row.attendedBy() == null ? null : new Owner(row.attendedBy(), names.get(row.attendedBy())),
                    row.forwardedAt() != null, row.createdAt(), row.attendedAt(), row.forwardedAt(), row.version()));
        }
        return List.copyOf(issues);
    }

    /** Dono: o responsável atual da comanda (garçom responsável) ou quem atende (central). */
    private static UUID owner(IssueRow row, Tabs.Label label) {
        if (row.mode() == AttendanceMode.CENTRAL) {
            return row.attendedBy();
        }
        return label == null ? null : label.responsibleId();
    }

    private static GuestIssue guestIssue(IssueRow row, List<Orders.TabItem> items) {
        Item item = row.orderLineId() == null ? null : new Item(row.orderLineId(), items.stream()
                .filter(i -> i.orderLineId().equals(row.orderLineId())).map(Orders.TabItem::productName)
                .findFirst().orElse(null));
        return new GuestIssue(row.id(), item, row.aboutCouvert(), row.description(), row.status(), row.afterClosure(), row.createdAt(),
                row.attendedAt(), row.concludedAt(), row.resolution());
    }
}
