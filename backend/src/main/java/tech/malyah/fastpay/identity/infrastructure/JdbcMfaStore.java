package tech.malyah.fastpay.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Autenticador, códigos de recuperação e trilha de segurança da identidade (H09). Todos os métodos rodam
 * no contexto {@code asIdentity} da própria pessoa; quem chama abre a transação com
 * {@code TenantTransactions}.
 */
@Repository
public class JdbcMfaStore {

    private final JdbcTemplate jdbc;

    JdbcMfaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Authenticator(String secretCiphertext, long lastUsedStep) {
    }

    public Optional<Authenticator> authenticator(UUID identityId) {
        return jdbc.query("SELECT secret_ciphertext, last_used_step FROM user_mfa WHERE identity_id = ?",
                (rs, n) -> new Authenticator(rs.getString("secret_ciphertext"), rs.getLong("last_used_step")),
                identityId).stream().findFirst();
    }

    /**
     * Grava o passo TOTP como usado se for posterior ao último. Só uma de duas verificações simultâneas
     * com o mesmo código consegue: a segunda espera o bloqueio da linha e já encontra o passo gravado.
     */
    public boolean advanceStep(UUID identityId, long step) {
        return jdbc.update("""
                UPDATE user_mfa SET last_used_step = ?, version = version + 1
                 WHERE identity_id = ? AND last_used_step < ?
                """, step, identityId, step) == 1;
    }

    /** Grava ou troca o autenticador. O passo do código de confirmação já conta como usado. */
    public boolean saveAuthenticator(UUID identityId, String secretCiphertext, long confirmedStep, Instant at) {
        Boolean replaced = jdbc.queryForObject("""
                INSERT INTO user_mfa (identity_id, secret_ciphertext, enrolled_at, last_used_step) VALUES (?, ?, ?, ?)
                ON CONFLICT (identity_id) DO UPDATE
                   SET secret_ciphertext = excluded.secret_ciphertext, enrolled_at = excluded.enrolled_at,
                       last_used_step = excluded.last_used_step, version = user_mfa.version + 1
                RETURNING version > 0
                """, Boolean.class, identityId, secretCiphertext, Timestamp.from(at), confirmedStep);
        return Boolean.TRUE.equals(replaced);
    }

    /** Invalida os códigos em aberto e grava os novos (só hashes). */
    public void replaceRecoveryCodes(UUID identityId, List<String> codeHashes, Instant at) {
        jdbc.update("""
                UPDATE mfa_recovery_code SET invalidated_at = ?
                 WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(at), identityId);
        for (String hash : codeHashes) {
            jdbc.update("INSERT INTO mfa_recovery_code (id, identity_id, code_hash, created_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), identityId, hash, Timestamp.from(at));
        }
    }

    /** Marca o código como usado se ainda estiver em aberto; só uma de duas tentativas simultâneas consegue. */
    public boolean consumeRecoveryCode(UUID identityId, String codeHash, Instant at) {
        return jdbc.update("""
                UPDATE mfa_recovery_code SET used_at = ?
                 WHERE identity_id = ? AND code_hash = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(at), identityId, codeHash) == 1;
    }

    public int openRecoveryCodes(UUID identityId) {
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM mfa_recovery_code
                 WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Integer.class, identityId);
        return open == null ? 0 : open;
    }

    public void securityEvent(UUID identityId, String action, String actor, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO security_event (id, identity_id, occurred_at, action, actor, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), identityId, Timestamp.from(at), action, actor, reason);
    }
}
