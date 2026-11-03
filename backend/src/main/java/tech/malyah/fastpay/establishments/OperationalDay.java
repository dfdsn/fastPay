package tech.malyah.fastpay.establishments;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Dia operacional da unidade que contém um instante (FR-57): data local, início e fim (exclusivo) em UTC, com o
 * fuso, o corte e o calendário vigentes na época, para que relatórios guardem a classificação original mesmo depois
 * de uma mudança de corte ou fuso.
 */
public record OperationalDay(LocalDate date, Instant start, Instant end, String timeZone, LocalTime cutoff,
        UUID calendarId) {
}
