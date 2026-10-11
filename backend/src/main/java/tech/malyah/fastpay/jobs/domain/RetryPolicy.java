package tech.malyah.fastpay.jobs.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Decide o que acontece depois de uma tentativa falhar (especificação §9, D-23).
 *
 * <p>Não financeiros têm até {@code maxAttempts} tentativas no total, contando a primeira; ao
 * esgotar, o job vai para a fila de falhas. Financeiros não têm teto: a conciliação continua até
 * haver situação definitiva. O intervalo cresce de forma geométrica a partir de {@code initialDelay}
 * e fica limitado a {@code maxDelay}.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts deve ser pelo menos 1");
        }
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Intervalos devem ser positivos e maxDelay >= initialDelay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier deve ser pelo menos 1");
        }
    }

    /** Teto gravado no job ao enfileirar; {@code null} significa sem teto (financeiro). */
    public Integer maxAttemptsFor(boolean financial) {
        return financial ? null : maxAttempts;
    }

    /**
     * @param attemptsMade tentativas já feitas, incluindo a que acabou de falhar (1 na primeira falha)
     * @param maxAttempts  teto gravado no job; {@code null} para financeiro
     */
    public Decision afterFailure(int attemptsMade, Integer maxAttempts) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade deve ser pelo menos 1");
        }
        if (maxAttempts != null && attemptsMade >= maxAttempts) {
            return Decision.exhausted();
        }
        return Decision.retryAfter(delayAfter(attemptsMade));
    }

    Duration delayAfter(int attemptsMade) {
        double factor = Math.pow(multiplier, attemptsMade - 1);
        double millis = initialDelay.toMillis() * factor;
        if (Double.isInfinite(millis) || millis >= maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis(Math.round(millis));
    }

    /** Resultado: nova tentativa depois de {@code delay}, ou fila de falhas ({@code delay} nulo). */
    public record Decision(boolean retry, Duration delay) {

        static Decision retryAfter(Duration delay) {
            return new Decision(true, delay);
        }

        static Decision exhausted() {
            return new Decision(false, null);
        }
    }
}
