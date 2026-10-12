package tech.malyah.fastpay.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.identity.MembershipRole;

/**
 * Credenciais e tokens de redefinição (H08). Cada método indica o contexto de RLS em que roda; quem
 * chama abre a transação com {@code TenantTransactions}.
 */
@Repository
public class JdbcCredentialStore {

    private final JdbcTemplate jdbc;

    JdbcCredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(UUID id, String email, String displayName) {
    }

    public record ResetToken(UUID identityId, Instant expiresAt, boolean open) {
    }

    /** Contexto {@code forLoginEmail}: a identidade do e-mail, se existir. */
    public Optional<Account> accountByEmail(String normalizedEmail) {
        return jdbc.query("SELECT id, email, display_name FROM user_identity WHERE email = ?",
                (rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name")),
                normalizedEmail).stream().findFirst();
    }

    /** Contexto {@code asIdentity}. */
    public Optional<Account> account(UUID identityId) {
        return jdbc.query("SELECT id, email, display_name FROM user_identity WHERE id = ?",
                (rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name")),
                identityId).stream().findFirst();
    }

    /** Contexto {@code asIdentity}. */
    public Optional<String> passwordHash(UUID identityId) {
        return jdbc.queryForList("SELECT password_hash FROM user_credential WHERE identity_id = ?", String.class,
                identityId).stream().findFirst();
    }

    /** Contexto {@code asIdentity}: papel global, se houver. */
    public boolean isPlatformAdmin(UUID identityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT coalesce(bool_or(platform_role = 'ADMIN_FASTPAY'), false) FROM user_identity WHERE id = ?",
                Boolean.class, identityId));
    }

    /** Contexto {@code asIdentity}: papéis dos vínculos ativos em todas as unidades. */
    public List<MembershipRole> activeRoles(UUID identityId) {
        return jdbc.queryForList("""
                SELECT DISTINCT role FROM membership
                 WHERE identity_id = ? AND identity_id = current_identity_id() AND status = 'ACTIVE'
                """, String.class, identityId).stream().map(MembershipRole::valueOf).toList();
    }

    /** Contexto {@code asIdentity}: grava ou troca o hash da senha. */
    public void savePasswordHash(UUID identityId, String passwordHash, Instant changedAt) {
        jdbc.update("""
                INSERT INTO user_credential (identity_id, password_hash, changed_at) VALUES (?, ?, ?)
                ON CONFLICT (identity_id) DO UPDATE
                   SET password_hash = excluded.password_hash, changed_at = excluded.changed_at,
                       version = user_credential.version + 1
                """, identityId, passwordHash, Timestamp.from(changedAt));
    }

    /** Contexto {@code asIdentity}: invalida o token em aberto, se houver. */
    public void invalidateOpenResetTokens(UUID identityId, Instant at) {
        jdbc.update("""
                UPDATE password_reset_token SET invalidated_at = ?
                 WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(at), identityId);
    }

    /** Contexto {@code asIdentity}. */
    public void insertResetToken(UUID identityId, String tokenHash, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO password_reset_token (id, identity_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), identityId, tokenHash, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    /** Contexto {@code forResetToken}: o token apresentado, se existir. */
    public Optional<ResetToken> resetToken(String tokenHash) {
        return jdbc.query("""
                SELECT identity_id, expires_at, used_at IS NULL AND invalidated_at IS NULL AS open
                  FROM password_reset_token WHERE token_hash = ?
                """, (rs, n) -> new ResetToken(rs.getObject("identity_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("open")),
                tokenHash).stream().findFirst();
    }

    /**
     * Contexto {@code asIdentity}: marca o token como usado se ainda estiver em aberto e no prazo. Só uma
     * de duas confirmações simultâneas consegue (a linha fica travada pela primeira).
     */
    public boolean consumeResetToken(UUID identityId, String tokenHash, Instant at) {
        return jdbc.update("""
                UPDATE password_reset_token SET used_at = ?
                 WHERE token_hash = ? AND identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(at), tokenHash, identityId, Timestamp.from(at)) == 1;
    }
}
