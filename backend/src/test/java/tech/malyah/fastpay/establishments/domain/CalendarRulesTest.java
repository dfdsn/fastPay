package tech.malyah.fastpay.establishments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.establishments.OperationalDay;
import tech.malyah.fastpay.establishments.domain.CalendarRules.Calendar;

/** Dia operacional por fuso e corte e mudança com vigência futura (FR-57, CA-26). */
class CalendarRulesTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final ZoneId MANAUS = ZoneId.of("America/Manaus");
    static final LocalTime SEIS = LocalTime.of(6, 0);

    static Instant sp(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(SP).toInstant();
    }

    static Calendar inicial() {
        return new Calendar(UUID.randomUUID(), SP, SEIS, sp("2026-01-01T00:00"), null);
    }

    @Test
    void madrugadaAntesDoCortePertenceAoDiaAnterior() {
        List<Calendar> c = List.of(inicial());
        OperationalDay madrugada = CalendarRules.dayOf(sp("2026-11-04T05:59:59"), c);
        assertThat(madrugada.date()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(madrugada.start()).isEqualTo(sp("2026-11-03T06:00"));
        assertThat(madrugada.end()).isEqualTo(sp("2026-11-04T06:00"));
        assertThat(CalendarRules.dayOf(sp("2026-11-04T06:00"), c).date()).isEqualTo(LocalDate.parse("2026-11-04"));
    }

    @Test
    void corteMaisTardeAlongaODiaDeTransicaoSemRepetirData() {
        Calendar antigo = inicial();
        OperationalDay hoje = CalendarRules.dayOf(sp("2026-11-03T22:00"), List.of(antigo));
        CalendarRules.ChangeStart inicio = CalendarRules.changeStart(hoje);
        assertThat(inicio.effectiveFrom()).isEqualTo(sp("2026-11-04T06:00"));
        assertThat(inicio.firstDate()).isEqualTo(LocalDate.parse("2026-11-04"));
        List<Calendar> c = List.of(antigo,
                new Calendar(UUID.randomUUID(), SP, LocalTime.of(8, 0), inicio.effectiveFrom(), inicio.firstDate()));

        // Passado não muda: a madrugada do dia 3 continua no dia 3, pelo corte antigo.
        OperationalDay passado = CalendarRules.dayOf(sp("2026-11-04T05:00"), c);
        assertThat(passado.date()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(passado.cutoff()).isEqualTo(SEIS);
        assertThat(passado.end()).isEqualTo(sp("2026-11-04T06:00"));

        // Dia 4 vai das 06:00 até o primeiro corte novo de data maior: 08:00 do dia 5 (26 h).
        OperationalDay transicao = CalendarRules.dayOf(sp("2026-11-04T07:00"), c);
        assertThat(transicao.date()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(transicao.start()).isEqualTo(sp("2026-11-04T06:00"));
        assertThat(transicao.end()).isEqualTo(sp("2026-11-05T08:00"));
        assertThat(CalendarRules.dayOf(sp("2026-11-05T07:59"), c).date()).isEqualTo(LocalDate.parse("2026-11-04"));

        OperationalDay depois = CalendarRules.dayOf(sp("2026-11-05T08:00"), c);
        assertThat(depois.date()).isEqualTo(LocalDate.parse("2026-11-05"));
        assertThat(depois.start()).isEqualTo(sp("2026-11-05T08:00"));
        assertThat(depois.end()).isEqualTo(sp("2026-11-06T08:00"));
    }

    @Test
    void corteMaisCedoEncurtaODiaDeTransicao() {
        Calendar antigo = inicial();
        CalendarRules.ChangeStart inicio = CalendarRules.changeStart(
                CalendarRules.dayOf(sp("2026-11-03T12:00"), List.of(antigo)));
        List<Calendar> c = List.of(antigo,
                new Calendar(UUID.randomUUID(), SP, LocalTime.of(4, 0), inicio.effectiveFrom(), inicio.firstDate()));
        OperationalDay transicao = CalendarRules.dayOf(sp("2026-11-05T03:59"), c);
        assertThat(transicao.date()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(Duration.between(transicao.start(), transicao.end())).isEqualTo(Duration.ofHours(22));
        assertThat(CalendarRules.dayOf(sp("2026-11-05T04:00"), c).date()).isEqualTo(LocalDate.parse("2026-11-05"));
    }

    @Test
    void trocaDeFusoValeNoProximoDiaENaoPulaData() {
        Calendar antigo = inicial();
        CalendarRules.ChangeStart inicio = CalendarRules.changeStart(
                CalendarRules.dayOf(sp("2026-11-03T12:00"), List.of(antigo)));
        List<Calendar> c = List.of(antigo,
                new Calendar(UUID.randomUUID(), MANAUS, SEIS, inicio.effectiveFrom(), inicio.firstDate()));
        // 06:00 em São Paulo = 05:00 em Manaus: o dia 4 vai até 06:00 de Manaus do dia 5 (25 h).
        OperationalDay transicao = CalendarRules.dayOf(sp("2026-11-04T06:30"), c);
        assertThat(transicao.date()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(transicao.timeZone()).isEqualTo("America/Manaus");
        assertThat(Duration.between(transicao.start(), transicao.end())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void diaAtualTerminaQuandoAMudancaAgendadaComeca() {
        Calendar antigo = inicial();
        Calendar pendente = new Calendar(UUID.randomUUID(), SP, LocalTime.of(8, 0), sp("2026-11-04T06:00"),
                LocalDate.parse("2026-11-04"));
        OperationalDay hoje = CalendarRules.dayOf(sp("2026-11-03T22:00"), List.of(pendente, antigo));
        assertThat(hoje.date()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(hoje.end()).isEqualTo(pendente.effectiveFrom());
        assertThat(hoje.calendarId()).isEqualTo(antigo.id());
    }

    @Test
    void fusoECorteValidos() {
        assertThat(CalendarRules.zone("America/Sao_Paulo")).contains(SP);
        assertThat(CalendarRules.zone(" America/Manaus ")).contains(MANAUS);
        assertThat(CalendarRules.zone("UTC")).isEmpty();
        assertThat(CalendarRules.zone("-03:00")).isEmpty();
        assertThat(CalendarRules.zone("Etc/GMT+3")).isEmpty();
        assertThat(CalendarRules.zone("America/Atlantida")).isEmpty();
        assertThat(CalendarRules.zone(null)).isEmpty();

        assertThat(CalendarRules.cutoff("00:00")).contains(LocalTime.MIDNIGHT);
        assertThat(CalendarRules.cutoff("12:00")).contains(LocalTime.NOON);
        assertThat(CalendarRules.cutoff("12:01")).isEmpty();
        assertThat(CalendarRules.cutoff("6:00")).isEmpty();
        assertThat(CalendarRules.cutoff("06:60")).isEmpty();
        assertThat(CalendarRules.cutoff("06:00:30")).isEmpty();
    }
}
