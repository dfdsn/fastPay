package tech.malyah.fastpay.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Limite de tentativas numa janela deslizante (T-03): bloqueia ao atingir {@code max} na janela. */
public record AttemptLimit(int max, Duration window) {

    public AttemptLimit {
        Objects.requireNonNull(window, "window");
        if (max < 1) {
            throw new IllegalArgumentException("Limite de tentativas deve ser positivo");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Janela de tentativas deve ser positiva");
        }
    }

    /** Início da janela que termina em {@code now}. */
    public Instant since(Instant now) {
        return now.minus(window);
    }

    public boolean reached(long attemptsInWindow) {
        return attemptsInWindow >= max;
    }
}
