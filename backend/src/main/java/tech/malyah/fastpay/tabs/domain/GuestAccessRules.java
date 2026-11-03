package tech.malyah.fastpay.tabs.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Validade do QR de vínculo e do acesso avulso (H15, FR-08, FR-11, CA-23, especificação §5). Puras, com o instante
 * de agora vindo de quem chama.
 */
public final class GuestAccessRules {

    /** Acesso avulso depois da resolução final (FR-11). */
    public static final Duration AFTER_RESOLUTION = Duration.ofDays(7);

    private GuestAccessRules() {
    }

    /** QR ainda vale: não consumido, não substituído e dentro do prazo (2 min, D-17). */
    public static boolean bindingUsable(Instant expiresAt, Instant consumedAt, Instant invalidatedAt, Instant now) {
        return consumedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

    /**
     * Acesso avulso vale enquanto não revogado e, depois da resolução final, até {@code accessUntil}. Prazo nulo:
     * comanda em atendimento ou com pendência (débito, pagamento incerto, devolução, problema), sem expiração por
     * tempo. Revogação de segurança prevalece sempre.
     */
    public static boolean grantValid(Instant revokedAt, Instant accessUntil, Instant now) {
        return revokedAt == null && (accessUntil == null || now.isBefore(accessUntil));
    }

    /**
     * Prazo do acesso avulso a gravar quando a comanda muda de situação depois de encerrada (FR-11, CA-23): com
     * pendência aberta não há prazo; sem pendência, {@link #AFTER_RESOLUTION} contado da última resolução. Uma
     * pendência nova suspende de novo e a resolução seguinte reinicia o prazo inteiro. Gravado pelas histórias de
     * encerramento, pagamento e problema (H21, H29–H32).
     */
    public static Instant accessUntil(Instant lastResolution, boolean pending) {
        return pending ? null : lastResolution.plus(AFTER_RESOLUTION);
    }
}
