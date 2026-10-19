package tech.malyah.fastpay.testsupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Relógio controlado compartilhado pelos testes de API que o importam (um contexto Spring só para eles). Em
 * microssegundos, como o PostgreSQL guarda: prazos comparados sem arredondamento.
 */
@TestConfiguration
public class ControlledClock {

    @Bean
    @Primary
    AdjustableClock relogio() {
        return new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
