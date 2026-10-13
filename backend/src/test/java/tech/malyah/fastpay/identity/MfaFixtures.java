package tech.malyah.fastpay.identity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

import tech.malyah.fastpay.identity.domain.Totp;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;

/**
 * Autenticador de teste (H09): grava o segredo cifrado como a aplicação grava e gera os códigos que o
 * aplicativo mostraria. Cada código entregue avança o passo, porque o servidor não aceita o mesmo passo
 * duas vezes; cabem até três logins da mesma pessoa no mesmo passo de 30 s.
 */
public final class MfaFixtures {

    private final JdbcTemplate admin;
    private final MfaSecretCipher cipher;
    private final Map<UUID, byte[]> secrets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSteps = new ConcurrentHashMap<>();

    public MfaFixtures(JdbcTemplate admin, MfaSecretCipher cipher) {
        this.admin = admin;
        this.cipher = cipher;
    }

    public byte[] enroll(UUID identityId) {
        byte[] secret = Totp.newSecret();
        admin.update("""
                INSERT INTO fastpay.user_mfa (identity_id, secret_ciphertext, enrolled_at, last_used_step)
                VALUES (?, ?, now(), 0)
                """, identityId, cipher.encrypt(identityId, secret));
        secrets.put(identityId, secret);
        return secret;
    }

    public boolean enrolled(UUID identityId) {
        return secrets.containsKey(identityId);
    }

    /** Usa um segredo cadastrado pela API (tela de configuração). */
    public void remember(UUID identityId, byte[] secret) {
        secrets.put(identityId, secret);
        lastSteps.remove(identityId);
    }

    /** Próximo código aceito pelo servidor no instante dado. */
    public String nextCode(UUID identityId, Instant now) {
        long current = Totp.step(now);
        long step = Math.max(current, lastSteps.getOrDefault(identityId, Long.MIN_VALUE) + 1);
        if (step > current + Totp.WINDOW) {
            throw new IllegalStateException("Logins demais da mesma pessoa no mesmo passo de 30 s; avance o relógio");
        }
        lastSteps.put(identityId, step);
        return Totp.code(secrets.get(identityId), step);
    }
}
