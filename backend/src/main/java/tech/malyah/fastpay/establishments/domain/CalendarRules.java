package tech.malyah.fastpay.establishments.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tech.malyah.fastpay.establishments.OperationalDay;

/**
 * Dia operacional por fuso e corte (FR-57, CA-26, especificação §7). Regras puras: quem chama lê os calendários
 * ativos (não cancelados) da unidade.
 *
 * <p>Um instante {@code t} pertence ao dia {@code d} quando {@code d às corte <= t < (d+1) às corte}, no fuso da
 * unidade. Uma mudança de fuso ou corte só começa no início do dia operacional seguinte ({@link #changeStart}); o
 * dia de transição vai desse início até o primeiro corte novo de data maior, então fica mais longo ou mais curto,
 * sem repetir nem pular data e sem mudar a classificação do que já passou.
 */
public final class CalendarRules {

    /** Corte mais tarde aceito: madrugada ou manhã (proposta T-03 da H14). */
    public static final LocalTime CUTOFF_MAX = LocalTime.NOON;

    /** Um calendário: fuso, corte, a partir de quando vale e a data do primeiro dia (nula no inicial). */
    public record Calendar(UUID id, ZoneId zone, LocalTime cutoff, Instant effectiveFrom, LocalDate firstDate) {
    }

    private CalendarRules() {
    }

    /** Data do dia operacional natural (sem transição) que contém o instante. */
    public static LocalDate naturalDate(Instant t, ZoneId zone, LocalTime cutoff) {
        ZonedDateTime local = t.atZone(zone);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(cutoff) ? date.minusDays(1) : date;
    }

    /** Início do dia operacional natural {@code date}; numa lacuna de horário de verão, o instante seguinte. */
    public static Instant naturalStart(LocalDate date, ZoneId zone, LocalTime cutoff) {
        return date.atTime(cutoff).atZone(zone).toInstant();
    }

    /**
     * Dia operacional que contém {@code t}, pelos calendários ativos da unidade (qualquer ordem, pelo menos um).
     * Antes do primeiro calendário vale o primeiro, em dias naturais.
     */
    public static OperationalDay dayOf(Instant t, List<Calendar> calendars) {
        if (calendars.isEmpty()) {
            throw new IllegalArgumentException("Unidade sem calendário operacional");
        }
        List<Calendar> ordered = calendars.stream().sorted(Comparator.comparing(Calendar::effectiveFrom)).toList();
        int index = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (!ordered.get(i).effectiveFrom().isAfter(t)) {
                index = i;
            }
        }
        Calendar current = ordered.get(index);
        Calendar next = index + 1 < ordered.size() ? ordered.get(index + 1) : null;
        LocalDate date;
        Instant start;
        Instant end;
        Instant transitionEnd = transitionEnd(current);
        if (transitionEnd != null && !t.isBefore(current.effectiveFrom()) && t.isBefore(transitionEnd)) {
            date = current.firstDate();
            start = current.effectiveFrom();
            end = transitionEnd;
        } else {
            date = naturalDate(t, current.zone(), current.cutoff());
            start = naturalStart(date, current.zone(), current.cutoff());
            end = naturalStart(date.plusDays(1), current.zone(), current.cutoff());
            if (transitionEnd != null && start.isBefore(transitionEnd)) {
                start = transitionEnd;
            }
        }
        if (next != null && end.isAfter(next.effectiveFrom())) {
            end = next.effectiveFrom();
        }
        return new OperationalDay(date, start, end, current.zone().getId(), current.cutoff(), current.id());
    }

    /**
     * Fim do dia de transição de um calendário que substituiu outro: primeiro corte novo depois do início com data
     * maior que a do primeiro dia. Nulo no calendário inicial.
     */
    private static Instant transitionEnd(Calendar calendar) {
        if (calendar.firstDate() == null) {
            return null;
        }
        LocalDate date = calendar.firstDate().plusDays(1);
        Instant end = naturalStart(date, calendar.zone(), calendar.cutoff());
        while (!end.isAfter(calendar.effectiveFrom())) {
            date = date.plusDays(1);
            end = naturalStart(date, calendar.zone(), calendar.cutoff());
        }
        return end;
    }

    /** Início e data do primeiro dia de uma mudança feita durante {@code today}: o dia operacional seguinte. */
    public record ChangeStart(Instant effectiveFrom, LocalDate firstDate) {
    }

    public static ChangeStart changeStart(OperationalDay today) {
        return new ChangeStart(today.end(), today.date().plusDays(1));
    }

    /**
     * Fuso IANA de região (ex.: {@code America/Sao_Paulo}); recusa deslocamentos fixos como {@code -03:00} ou
     * {@code UTC}, que ignoram as regras locais.
     */
    public static Optional<ZoneId> zone(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 64) {
            return Optional.empty();
        }
        String id = raw.strip();
        if (!ZoneId.getAvailableZoneIds().contains(id) || !id.contains("/") || id.startsWith("Etc/")) {
            return Optional.empty();
        }
        try {
            ZoneId zone = ZoneId.of(id);
            return zone instanceof ZoneOffset ? Optional.empty() : Optional.of(zone);
        } catch (DateTimeException invalid) {
            return Optional.empty();
        }
    }

    /** Corte no formato {@code HH:mm}, de 00:00 a 12:00. */
    public static Optional<LocalTime> cutoff(String raw) {
        if (raw == null || !raw.strip().matches("\\d{2}:\\d{2}")) {
            return Optional.empty();
        }
        try {
            LocalTime time = LocalTime.parse(raw.strip());
            return time.isAfter(CUTOFF_MAX) ? Optional.empty() : Optional.of(time);
        } catch (DateTimeException invalid) {
            return Optional.empty();
        }
    }
}
