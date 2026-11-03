package tech.malyah.fastpay.establishments;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.domain.CalendarRules;
import tech.malyah.fastpay.establishments.domain.CalendarRules.Calendar;
import tech.malyah.fastpay.establishments.domain.SettingsRules;
import tech.malyah.fastpay.establishments.infrastructure.JdbcSettingsStore;
import tech.malyah.fastpay.establishments.infrastructure.JdbcSettingsStore.VersionRow;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;

/**
 * Configuração versionada da unidade (H14; FR-23, FR-28–30, FR-40, FR-57, FR-68; D-08).
 *
 * <p>Ver: gerente e administrador ({@link Permission#VER_CONFIGURACAO}). Alterar: administrador
 * ({@link Permission#CONFIGURAR_ESTABELECIMENTO}), com a versão que a tela leu: se outra pessoa salvou antes, a
 * mudança é recusada. Salvar valores diferentes cria a versão seguinte, imutável, com auditoria; salvar os mesmos
 * valores não cria nada. Mudança de fuso ou corte é agendada para o início do próximo dia operacional e pode ser
 * substituída ou cancelada até lá.
 *
 * <p>Captura (para as histórias de comanda): {@link #currentInCurrentUnit()} na abertura devolve a versão cujo id a
 * comanda guarda; {@link #versionInCurrentUnit(UUID)} relê essa versão depois, sem efeito de mudanças posteriores;
 * {@link #dayInCurrentUnit(Instant)} classifica um instante no dia operacional da época. Esses três métodos rodam
 * na transação de unidade de quem chama, que já conferiu vínculo e permissão.
 */
@Service
public class EstablishmentSettings {

    private static final Logger log = LoggerFactory.getLogger(EstablishmentSettings.class);
    private static final int HISTORY = 10;

    /** Calendário em uso desde {@code since}. */
    public record CalendarView(String timeZone, LocalTime cutoff, Instant since) {
    }

    /** Mudança de fuso/corte agendada: vale a partir de {@code effectiveFrom}, no dia {@code firstDate}. */
    public record PendingCalendar(String timeZone, LocalTime cutoff, Instant effectiveFrom, LocalDate firstDate) {
    }

    /** Versão salva: número, quando, por quem (nulo no padrão inicial) e o que mudou em relação à anterior. */
    public record HistoryEntry(int number, Instant createdAt, String author, List<String> changed) {
    }

    /** {@code editable}: quem pediu pode alterar (administrador). */
    public record View(SettingsVersion current, CalendarView calendar, PendingCalendar pendingCalendar,
            OperationalDay today, List<HistoryEntry> history, boolean editable) {
    }

    public sealed interface Outcome {
    }

    public record Done(View view) implements Outcome {
    }

    public record Rejected(List<SettingsRules.Problem> problems) implements Outcome {
    }

    /** Outra pessoa salvou depois da leitura: a tela recarrega e confere. */
    public record Outdated(int currentNumber) implements Outcome {
    }

    private final Memberships memberships;
    private final JdbcSettingsStore store;
    private final Clock clock;

    EstablishmentSettings(Memberships memberships, JdbcSettingsStore store, Clock clock) {
        this.memberships = memberships;
        this.store = store;
        this.clock = clock;
    }

    public View view(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.VER_CONFIGURACAO,
                me -> view(me.has(Permission.CONFIGURAR_ESTABELECIMENTO)));
    }

    /**
     * Salva a configuração lida na versão {@code expectedNumber}. Valores comerciais diferentes criam a versão
     * seguinte; fuso/corte diferentes do calendário em uso agendam a mudança para o próximo dia operacional
     * (substituindo uma mudança pendente); iguais ao em uso cancelam a pendente.
     */
    public Outcome update(UUID actorId, UUID establishmentId, int expectedNumber, SettingsRules.Draft draft,
            String correlationId) {
        Objects.requireNonNull(draft, "draft");
        SettingsRules.Result checked = SettingsRules.check(draft);
        if (checked instanceof SettingsRules.Invalid invalid) {
            // Sem permissão a recusa é 403, mesmo com dados inválidos.
            memberships.inUnit(actorId, establishmentId, Permission.CONFIGURAR_ESTABELECIMENTO, me -> me);
            return new Rejected(invalid.problems());
        }
        SettingsRules.Valid valid = (SettingsRules.Valid) checked;
        return memberships.inUnit(actorId, establishmentId, Permission.CONFIGURAR_ESTABELECIMENTO, me -> {
            store.lockUnit();
            SettingsVersion latest = store.latest().orElseThrow();
            if (latest.number() != expectedNumber) {
                return new Outdated(latest.number());
            }
            Instant now = clock.instant();
            List<String> changed = SettingsRules.changed(latest.values(), valid.values());
            if (!changed.isEmpty()) {
                int number = latest.number() + 1;
                UUID id = store.insert(number, valid.values(), actorId, now);
                store.audit("configuracao.alterada", id, actorId, "v" + number + ": " + String.join(",", changed),
                        correlationId);
                log.info("Configuração da unidade {} na versão {} por {} ({})", establishmentId, number, actorId,
                        changed);
            }
            schedule(valid, actorId, now, correlationId);
            return new Done(view(true));
        });
    }

    /** Versão vigente da unidade do contexto, para a comanda guardar na abertura. */
    public SettingsVersion currentInCurrentUnit() {
        return store.latest().orElseThrow(() -> new IllegalStateException("Unidade sem configuração"));
    }

    /** Versão guardada por uma comanda; vazia se não é desta unidade. */
    public Optional<SettingsVersion> versionInCurrentUnit(UUID versionId) {
        return store.version(versionId);
    }

    /** Dia operacional da unidade do contexto que contém o instante, pelo calendário da época. */
    public OperationalDay dayInCurrentUnit(Instant instant) {
        return CalendarRules.dayOf(instant, store.calendars());
    }

    private void schedule(SettingsRules.Valid valid, UUID actorId, Instant now, String correlationId) {
        List<Calendar> calendars = store.calendars();
        List<Calendar> active = calendars.stream().filter(c -> !pending(c, now)).toList();
        Optional<Calendar> pending = calendars.stream().filter(c -> pending(c, now)).findFirst();
        Calendar inUse = active.getLast();
        if (pending.isPresent() && same(pending.get(), valid)) {
            return;
        }
        if (pending.isPresent()) {
            store.cancel(pending.get().id(), actorId, now);
            store.audit("calendario.mudanca_cancelada", pending.get().id(), actorId,
                    describe(pending.get().zone().getId(), pending.get().cutoff()), correlationId);
        }
        if (same(inUse, valid)) {
            return;
        }
        CalendarRules.ChangeStart start = CalendarRules.changeStart(CalendarRules.dayOf(now, active));
        UUID id = store.insertCalendar(valid.zone(), valid.cutoff(), start.effectiveFrom(), start.firstDate(),
                actorId, now);
        store.audit("calendario.mudanca_agendada", id, actorId,
                describe(valid.zone().getId(), valid.cutoff()) + " a partir do dia " + start.firstDate(),
                correlationId);
    }

    /** Mudança agendada que ainda não vale. O calendário inicial nunca é pendente. */
    private static boolean pending(Calendar calendar, Instant now) {
        return calendar.firstDate() != null && calendar.effectiveFrom().isAfter(now);
    }

    private static boolean same(Calendar calendar, SettingsRules.Valid valid) {
        return calendar.zone().equals(valid.zone()) && calendar.cutoff().equals(valid.cutoff());
    }

    private static String describe(String zone, LocalTime cutoff) {
        return zone + " corte " + cutoff;
    }

    private View view(boolean editable) {
        Instant now = clock.instant();
        List<VersionRow> rows = store.recent(HISTORY + 1);
        List<HistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < Math.min(HISTORY, rows.size()); i++) {
            VersionRow row = rows.get(i);
            List<String> changed = i + 1 < rows.size()
                    ? SettingsRules.changed(rows.get(i + 1).version().values(), row.version().values())
                    : List.of();
            history.add(new HistoryEntry(row.version().number(), row.version().createdAt(), row.author(), changed));
        }
        List<Calendar> calendars = store.calendars();
        Calendar inUse = calendars.stream().filter(c -> !pending(c, now)).reduce((a, b) -> b).orElseThrow();
        PendingCalendar pending = calendars.stream().filter(c -> pending(c, now)).findFirst()
                .map(c -> new PendingCalendar(c.zone().getId(), c.cutoff(), c.effectiveFrom(), c.firstDate()))
                .orElse(null);
        return new View(rows.getFirst().version(),
                new CalendarView(inUse.zone().getId(), inUse.cutoff(), inUse.effectiveFrom()), pending,
                CalendarRules.dayOf(now, calendars), history, editable);
    }
}
