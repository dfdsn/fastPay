package tech.malyah.fastpay.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Janela de validade de uma sessão autenticada. O início absoluto nunca muda (trocar de unidade não
 * estende a sessão); a última atividade só avança com ação da pessoa, não com polling, SSE ou heartbeat.
 */
public record SessionWindow(SessionProfile profile, Instant authenticatedAt, Instant lastActivityAt) {

    public SessionWindow {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
    }

    public static SessionWindow startingAt(SessionProfile profile, Instant now) {
        return new SessionWindow(profile, now, now);
    }

    public Instant idleDeadline() {
        return lastActivityAt.plus(profile.idleTimeout());
    }

    public Instant absoluteDeadline() {
        return authenticatedAt.plus(profile.absoluteTimeout());
    }

    /** Vence no instante do prazo: exatamente 30 minutos parado já não vale. */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(idleDeadline()) || !now.isBefore(absoluteDeadline());
    }

    public SessionWindow touchedAt(Instant now) {
        return now.isAfter(lastActivityAt) ? new SessionWindow(profile, authenticatedAt, now) : this;
    }
}
