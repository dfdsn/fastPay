package tech.malyah.fastpay.tabs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class GuestAccessRulesTest {

    private static final Instant AGORA = Instant.parse("2026-11-03T20:00:00Z");

    @Test
    void qrValeAteVencerSeNaoFoiUsadoNemSubstituido() {
        Instant vence = AGORA.plus(Duration.ofMinutes(2));
        assertThat(GuestAccessRules.bindingUsable(vence, null, null, AGORA)).isTrue();
        assertThat(GuestAccessRules.bindingUsable(vence, null, null, vence.minusNanos(1000))).isTrue();
        assertThat(GuestAccessRules.bindingUsable(vence, null, null, vence)).isFalse();
        assertThat(GuestAccessRules.bindingUsable(vence, AGORA, null, AGORA)).isFalse();
        assertThat(GuestAccessRules.bindingUsable(vence, null, AGORA, AGORA)).isFalse();
    }

    @Test
    void acessoSemPrazoValeAteSerRevogado() {
        assertThat(GuestAccessRules.grantValid(null, null, AGORA.plus(Duration.ofDays(400)))).isTrue();
        assertThat(GuestAccessRules.grantValid(AGORA, null, AGORA)).isFalse();
    }

    @Test
    void revogacaoPrevaleceSobreOPrazo() {
        assertThat(GuestAccessRules.grantValid(AGORA, AGORA.plus(Duration.ofDays(7)), AGORA)).isFalse();
    }

    @Test
    void pendenciaSuspendeEResolucaoReiniciaSeteDias() {
        assertThat(GuestAccessRules.accessUntil(AGORA, true)).isNull();
        Instant ate = GuestAccessRules.accessUntil(AGORA, false);
        assertThat(ate).isEqualTo(AGORA.plus(Duration.ofDays(7)));
        assertThat(GuestAccessRules.grantValid(null, ate, ate.minusSeconds(1))).isTrue();
        assertThat(GuestAccessRules.grantValid(null, ate, ate)).isFalse();
        // Chamado aberto no dia 7 e resolvido no dia 9: novo prazo de 7 dias a partir do dia 9 (CA-23).
        Instant resolvidoDepois = AGORA.plus(Duration.ofDays(9));
        assertThat(GuestAccessRules.accessUntil(resolvidoDepois, false)).isEqualTo(AGORA.plus(Duration.ofDays(16)));
    }
}
