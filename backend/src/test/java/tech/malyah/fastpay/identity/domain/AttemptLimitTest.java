package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AttemptLimitTest {

    @Test
    void bloqueiaAoAtingirOMaximoNaJanela() {
        AttemptLimit limite = new AttemptLimit(5, Duration.ofMinutes(15));

        assertThat(limite.reached(4)).isFalse();
        assertThat(limite.reached(5)).isTrue();
        assertThat(limite.since(Instant.parse("2026-10-12T18:15:00Z"))).isEqualTo(Instant.parse("2026-10-12T18:00:00Z"));
    }

    @Test
    void recusaConfiguracaoSemSentido() {
        assertThatThrownBy(() -> new AttemptLimit(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptLimit(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chavesDerivadas() {
        assertThat(AuthKeys.normalizeEmail("  Ana@Bar.COM ")).isEqualTo("ana@bar.com");
        assertThat(AuthKeys.normalizeEmail(null)).isEmpty();
        assertThat(AuthKeys.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(AuthKeys.newToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(AuthKeys.newToken());
    }
}
