package tech.malyah.fastpay.platform.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Concessões de suporte ({@code support_grant}, H11). Cada método indica o contexto de RLS em que roda; quem chama
 * abre a transação ({@code identity.Memberships.inUnit} para o dono, {@code TenantTransactions} para o suporte).
 */
@Repository
public class JdbcSupportGrantStore {

    private static final String COLUMNS = """
            g.id, g.establishment_id, e.name AS establishment_name, g.support_email, g.reason, g.granted_at,
            g.expires_at, g.revoked_at,
            (SELECT count(*) FROM audit_event a
              WHERE a.establishment_id = g.establishment_id AND a.subject_id = g.id
                AND a.action = 'suporte.consultado') AS consultations,
            (SELECT max(a.occurred_at) FROM audit_event a
              WHERE a.establishment_id = g.establishment_id AND a.subject_id = g.id
                AND a.action = 'suporte.consultado') AS last_consultation_at
            """;

    private final JdbcTemplate jdbc;

    JdbcSupportGrantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Concessão como gravada. {@code consultations} e {@code lastConsultationAt} vêm da auditoria da unidade e só
     * são lidos no contexto dela (zero e vazio na lista do suporte).
     */
    public record Grant(UUID id, UUID establishmentId, String establishmentName, String supportEmail, String reason,
            Instant grantedAt, Instant expiresAt, Instant revokedAt, long consultations, Instant lastConsultationAt) {

        public boolean activeAt(Instant now) {
            return revokedAt == null && now.isBefore(expiresAt);
        }
    }

    /** Qualquer contexto: id do admin fastPay com este e-mail; vazio para qualquer outra pessoa ou e-mail. */
    public Optional<UUID> supportIdentityByEmail(String email) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT support_identity_by_email(?)", UUID.class, email));
    }

    /** Contexto da unidade com a identidade do dono. */
    public void insert(UUID id, UUID supportIdentityId, String supportEmail, String reason, UUID grantedBy,
            Instant grantedAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO support_grant (id, establishment_id, support_identity_id, support_email, reason, granted_by,
                                           granted_at, expires_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?)
                """, id, supportIdentityId, supportEmail, reason, grantedBy, Timestamp.from(grantedAt),
                Timestamp.from(expiresAt));
    }

    /** Contexto da unidade: a identidade de suporte já tem concessão vigente aqui? */
    public boolean activeFor(UUID supportIdentityId, Instant now) {
        return !jdbc.queryForList("""
                SELECT 1 FROM support_grant
                 WHERE establishment_id = current_establishment_id() AND support_identity_id = ?
                   AND revoked_at IS NULL AND expires_at > ?
                """, Integer.class, supportIdentityId, Timestamp.from(now)).isEmpty();
    }

    /** Contexto da unidade: concessões, mais recentes primeiro, até 50. */
    public List<Grant> inCurrentUnit() {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM support_grant g JOIN establishment e ON e.id = g.establishment_id
                WHERE g.establishment_id = current_establishment_id()
                ORDER BY g.granted_at DESC, g.id DESC
                LIMIT 50
                """, JdbcSupportGrantStore::grant);
    }

    /** Contexto da unidade com a identidade do dono: encerra a concessão, se ainda vigente. */
    public boolean revoke(UUID grantId, UUID revokedBy, Instant at) {
        return jdbc.update("""
                UPDATE support_grant SET revoked_at = ?, revoked_by = ?
                 WHERE id = ? AND establishment_id = current_establishment_id() AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(at), revokedBy, grantId, Timestamp.from(at)) == 1;
    }

    /** Contexto da identidade de suporte, sem unidade: concessões vigentes dadas a ela. */
    public List<Grant> activeForCurrentIdentity(Instant now) {
        return jdbc.query("""
                SELECT g.id, g.establishment_id, e.name AS establishment_name, g.support_email, g.reason, g.granted_at,
                       g.expires_at, g.revoked_at, 0 AS consultations, NULL::timestamptz AS last_consultation_at
                  FROM support_grant g JOIN establishment e ON e.id = g.establishment_id
                 WHERE g.support_identity_id = current_identity_id() AND g.revoked_at IS NULL AND g.expires_at > ?
                 ORDER BY g.expires_at, g.id
                """, JdbcSupportGrantStore::grant, Timestamp.from(now));
    }

    /**
     * Contexto da unidade com a identidade de suporte: a concessão vigente para ela aqui, se ela ainda for admin
     * fastPay.
     */
    public Optional<UUID> activeGrantForCurrentIdentity(Instant now) {
        return jdbc.queryForList("""
                SELECT id FROM support_grant
                 WHERE establishment_id = current_establishment_id() AND support_identity_id = current_identity_id()
                   AND revoked_at IS NULL AND expires_at > ? AND current_platform_admin()
                 ORDER BY expires_at DESC, id
                 LIMIT 1
                """, UUID.class, Timestamp.from(now)).stream().findFirst();
    }

    /** Auditoria na unidade do contexto. */
    public void audit(String action, UUID grantId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, grantId, actorId, detail, correlationId);
    }

    private static Grant grant(ResultSet rs, int row) throws SQLException {
        return new Grant(rs.getObject("id", UUID.class), rs.getObject("establishment_id", UUID.class),
                rs.getString("establishment_name"), rs.getString("support_email"), rs.getString("reason"),
                instant(rs, "granted_at"), instant(rs, "expires_at"), instant(rs, "revoked_at"),
                rs.getLong("consultations"), instant(rs, "last_consultation_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
