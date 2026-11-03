package tech.malyah.fastpay.tabs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AccessTokensTest {

    @Test
    void tokenTem256BitsSemRepetirEOBancoGuardaSoOHash() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = AccessTokens.generate();
            assertThat(AccessTokens.wellFormed(token)).isTrue();
            assertThat(vistos.add(token)).isTrue();
        }
        String token = AccessTokens.generate();
        assertThat(AccessTokens.hash(token)).matches("[0-9a-f]{64}").isEqualTo(AccessTokens.hash(token))
                .doesNotContain(token);
    }

    @Test
    void formatoErradoERecusado() {
        assertThat(AccessTokens.wellFormed(null)).isFalse();
        assertThat(AccessTokens.wellFormed("")).isFalse();
        assertThat(AccessTokens.wellFormed("a".repeat(42))).isFalse();
        assertThat(AccessTokens.wellFormed("a".repeat(44))).isFalse();
        assertThat(AccessTokens.wellFormed("a".repeat(42) + "=")).isFalse();
        assertThat(AccessTokens.wellFormed("a".repeat(42) + ".")).isFalse();
    }
}
