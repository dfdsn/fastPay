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

import tech.malyah.fastpay.attendance.domain.CallRules;
import tech.malyah.fastpay.attendance.infrastructure.JdbcAttendanceStore;
import tech.malyah.fastpay.attendance.infrastructure.JdbcAttendanceStore.CallRow;
import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.production.Production;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;

/**
 * Fila de atendimento da unidade (H20, FR-23, FR-26, FR-27). Quem atende ({@link Permission#OPERAR_COMANDA}) vê todos
 * os chamados em aberto — os seus, os da central sem dono e os de outras pessoas, para ajudar — e entrega pelo
 * comando da produção, que conclui o aviso. Na central, assume um chamado de forma exclusiva. O gerente
 * ({@link Permission#REATRIBUIR_ATENDIMENTO}) reatribui o chamado da central; na modalidade garçom responsável troca o
 * responsável da comanda ({@link Tabs#reassign}). Ler a fila não conclui nada.
 */
@Service
public class Attendance {

    private static final Logger log = LoggerFactory.getLogger(Attendance.class);

    /** Comanda do chamado: número, nome e mesa. */
    public record TabRef(UUID id, int number, String name, String table) {
    }

    /** Quem responde pelo chamado agora. */
    public record Owner(UUID id, String name) {
    }

    /**
     * Um chamado em aberto. {@code mode}: a modalidade da comanda, capturada na abertura dela. {@code owner}: o
     * responsável da comanda (garçom responsável) ou quem assumiu (central); nulo na central sem dono. {@code mine}:
     * é de quem lê. {@code quantity}: as unidades da linha nesta comanda. {@code line}: o item, com a versão para
     * entregar; nulo se a linha não é mais legível.
     */
    public record Call(UUID id, CallKind kind, AttendanceMode mode, TabRef tab, Owner owner, boolean mine,
            int quantity, Instant createdAt, Instant claimedAt, Production.Line line, long version) {
    }

    /** Chamados em aberto, problemas do consumidor ainda não tratados (H21) e o instante do servidor. */
    public record Queue(Instant now, List<Call> calls, List<ConsumerIssues.Issue> issues) {
    }

    public enum Refusal {
        CHAMADO_NAO_ENCONTRADO,
        CHAMADO_CONCLUIDO,
        /** Garçom responsável: o chamado é do responsável da comanda; o gerente troca o responsável. */
        CHAMADO_COM_RESPONSAVEL,
        /** Central: outra pessoa já assumiu. */
        CHAMADO_JA_ASSUMIDO,
        VERSAO_DESATUALIZADA,
        /** H21: problema do consumidor. */
        PROBLEMA_NAO_ENCONTRADO,
        PROBLEMA_TRATADO,
        /** Central: outra pessoa já está atendendo o problema. */
        PROBLEMA_JA_ASSUMIDO,
        /** Garçom responsável: o problema é do responsável da comanda; o gerente troca o responsável. */
        PROBLEMA_COM_RESPONSAVEL,
        /** Encaminhar ou concluir antes de alguém começar a atender. */
        PROBLEMA_NAO_ATENDIDO,
        /** Problema após o encerramento: só quem autoriza devolução conclui. */
        PROBLEMA_EXIGE_GERENTE,
        /** Consumidor: já há um problema em aberto para este item (ou para a conta). */
        PROBLEMA_JA_ABERTO,
        /** Consumidor: o item não é desta comanda. */
        ITEM_NAO_ENCONTRADO
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal, String holder) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    private final Memberships memberships;
    private final Tabs tabs;
    private final Production production;
    private final JdbcAttendanceStore store;
    private final Changes changes;
    private final Clock clock;
    private final ConsumerIssues issues;

    Attendance(Memberships memberships, Tabs tabs, Production production, JdbcAttendanceStore store, Changes changes,
            Clock clock, ConsumerIssues issues) {
        this.issues = issues;
        this.memberships = memberships;
        this.tabs = tabs;
        this.production = production;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    /** Chamados em aberto da unidade, dos mais antigos aos mais novos. */
    public Queue queue(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA,
                me -> new Queue(clock.instant(), calls(store.open(), actorId), issues.pendingInCurrentUnit(actorId)));
    }

    /**
     * Assume um chamado da central (FR-23): exclusivo. Duas pessoas ao mesmo tempo: o chamado travado deixa passar
     * uma, e a outra recebe {@link Refusal#CHAMADO_JA_ASSUMIDO} com o nome de quem assumiu. Assumir de novo o próprio
     * chamado não muda nada.
     */
    public Outcome<Call> claim(UUID actorId, UUID establishmentId, UUID callId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            Optional<CallRow> row = store.lock(callId);
            if (row.isEmpty()) {
                return new Refused<>(Refusal.CHAMADO_NAO_ENCONTRADO, null);
            }
            return decide(actorId, row.get(), CallRules.claim(rule(row.get()), actorId), "chamado.assumido",
                    correlationId);
        });
    }

    /**
     * Reatribui um chamado da central (gerente, FR-23) a alguém ativo da equipe que atende, com a versão lida. Na
     * modalidade garçom responsável, o chamado segue o responsável da comanda: troca-se o responsável.
     */
    public Outcome<Call> reassign(UUID actorId, UUID establishmentId, UUID callId, Long version, UUID assigneeId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.REATRIBUIR_ATENDIMENTO, me -> {
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            if (assigneeId == null) {
                return new Invalid<>("assigneeId", "Escolha para quem passar o chamado.");
            }
            Optional<CallRow> row = store.lock(callId);
            if (row.isEmpty()) {
                return new Refused<>(Refusal.CHAMADO_NAO_ENCONTRADO, null);
            }
            CallRules.Decision decision = CallRules.reassign(rule(row.get()), version, assigneeId);
            if (decision instanceof CallRules.Assign
                    && !memberships.holdsInCurrentUnit(assigneeId, Permission.OPERAR_COMANDA)) {
                return new Invalid<>("assigneeId", "Escolha alguém ativo da equipe que atende comandas.");
            }
            return decide(actorId, row.get(), decision, "chamado.reatribuido", correlationId);
        });
    }

    /** Pessoas ativas da unidade que atendem, para o gerente escolher na reatribuição. */
    public List<IdentitySummary> attendants(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.REATRIBUIR_ATENDIMENTO,
                me -> memberships.holdersInCurrentUnit(Permission.OPERAR_COMANDA));
    }

    private Outcome<Call> decide(UUID actorId, CallRow row, CallRules.Decision decision, String audit,
            String correlationId) {
        return switch (decision) {
            case CallRules.Refused r -> new Refused<>(switch (r.refusal()) {
                case CONCLUIDO -> Refusal.CHAMADO_CONCLUIDO;
                case COM_RESPONSAVEL -> Refusal.CHAMADO_COM_RESPONSAVEL;
                case JA_ASSUMIDO -> Refusal.CHAMADO_JA_ASSUMIDO;
                case VERSAO_DESATUALIZADA -> Refusal.VERSAO_DESATUALIZADA;
            }, r.refusal() == CallRules.Refusal.JA_ASSUMIDO ? name(row.claimedBy()) : null);
            case CallRules.Unchanged u -> new Done<>(single(row, actorId));
            case CallRules.Assign a -> {
                Instant now = clock.instant();
                store.assign(row.id(), row.version(), a.assignee(), now);
                store.audit(audit, row.id(), actorId, (row.claimedBy() == null ? "sem dono" : "de " + row.claimedBy())
                        + " para " + a.assignee(), correlationId);
                changes.publishInCurrentTransaction(Topic.ATENDIMENTO, row.tabId());
                log.info("Chamado {} ({}) passou a {} por {}", row.id(), audit, a.assignee(), actorId);
                yield new Done<>(single(store.call(row.id()).orElseThrow(), actorId));
            }
        };
    }

    private String name(UUID identityId) {
        return identityId == null ? null : memberships.namesInCurrentUnit(List.of(identityId)).get(identityId);
    }

    private static CallRules.Call rule(CallRow row) {
        return new CallRules.Call(row.mode(), row.claimedBy(), row.concludedAt() != null, row.version());
    }

    private Call single(CallRow row, UUID actorId) {
        return calls(List.of(row), actorId).getFirst();
    }

    private List<Call> calls(List<CallRow> rows, UUID actorId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(rows.stream().map(CallRow::tabId).distinct().toList());
        Map<UUID, Production.Line> lines = production.linesInCurrentUnit(
                rows.stream().map(CallRow::lineId).distinct().toList());
        Set<UUID> people = new LinkedHashSet<>();
        for (CallRow row : rows) {
            UUID owner = owner(row, labels.get(row.tabId()));
            if (owner != null) {
                people.add(owner);
            }
        }
        Map<UUID, String> names = memberships.namesInCurrentUnit(List.copyOf(people));
        List<Call> calls = new ArrayList<>(rows.size());
        for (CallRow row : rows) {
            Tabs.Label label = labels.get(row.tabId());
            if (label == null) {
                continue;
            }
            UUID ownerId = owner(row, label);
            Production.Line line = lines.get(row.lineId());
            int quantity = line == null ? 0 : line.destinations().stream()
                    .filter(d -> d.tabId().equals(row.tabId())).mapToInt(Production.Destination::quantity).sum();
            calls.add(new Call(row.id(), row.kind(), row.mode(),
                    new TabRef(label.id(), label.number(), label.name(), label.table()),
                    ownerId == null ? null : new Owner(ownerId, names.get(ownerId)), Objects.equals(ownerId, actorId),
                    quantity, row.createdAt(), row.claimedAt(), line, row.version()));
        }
        return List.copyOf(calls);
    }

    /**
     * Dono atual: o responsável da comanda (garçom responsável, mesmo que tenha sido trocado depois do aviso) ou quem
     * assumiu (central). A modalidade é a do chamado, capturada da comanda: trocar a da unidade não migra nada.
     */
    private static UUID owner(CallRow row, Tabs.Label label) {
        if (row.mode() == AttendanceMode.CENTRAL) {
            return row.claimedBy();
        }
        return label == null ? null : label.responsibleId();
    }
}
