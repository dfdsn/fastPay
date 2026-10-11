package tech.malyah.fastpay.jobs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(15), 2.0, Duration.ofMinutes(5));

    @Test
    void naoFinanceiroTemCincoTentativasContandoAPrimeira() {
        assertThat(policy.maxAttemptsFor(false)).isEqualTo(5);
        for (int feitas = 1; feitas <= 4; feitas++) {
            assertThat(policy.afterFailure(feitas, 5).retry()).as("após %d tentativas", feitas).isTrue();
        }
        RetryPolicy.Decision quinta = policy.afterFailure(5, 5);
        assertThat(quinta.retry()).isFalse();
        assertThat(quinta.delay()).isNull();
    }

    @Test
    void financeiroNaoTemTeto() {
        assertThat(policy.maxAttemptsFor(true)).isNull();
        assertThat(policy.afterFailure(5, null).retry()).isTrue();
        assertThat(policy.afterFailure(500, null)).isEqualTo(new RetryPolicy.Decision(true, Duration.ofMinutes(5)));
    }

    @Test
    void intervaloCresceEFicaLimitado() {
        assertThat(policy.afterFailure(1, 5).delay()).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.afterFailure(2, 5).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.afterFailure(3, 5).delay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.afterFailure(4, 5).delay()).isEqualTo(Duration.ofSeconds(120));
        assertThat(policy.afterFailure(6, null).delay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.afterFailure(5000, null).delay()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void retomadaAumentaOTetoAlemDasTentativasFeitas() {
        assertThat(policy.afterFailure(7, 10).retry()).isTrue();
        assertThat(policy.afterFailure(10, 10).retry()).isFalse();
    }

    @Test
    void configuracaoInvalidaEhRecusada() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 2, Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(5, Duration.ofSeconds(10), 2, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(5, Duration.ofSeconds(-1), 2, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(5, Duration.ZERO, 0.5, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.afterFailure(0, 5));
    }
}
