package tech.malyah.fastpay.identity.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.UnitMembership;
import tech.malyah.fastpay.identity.domain.AccessPolicy;

/**
 * Acesso às tabelas {@code user_identity}, {@code membership} e {@code membership_permission}. Todas as consultas
 * rodam sob RLS com o contexto já definido pela transação ({@code TenantTransactions}); os filtros abaixo são a
 * regra, as policies são a garantia.
 */
@Repository
public class JdbcMembershipStore {

    private static final String DELEGATED = """
            ARRAY(SELECT p.permission FROM membership_permission p
                   WHERE p.membership_id = m.id AND p.revoked_at IS NULL ORDER BY p.permission) AS delegated
            """;

    private static final String UNIT_COLUMNS = """
            m.id AS membership_id, m.identity_id, m.establishment_id, e.name AS establishment_name, m.role,
            """ + DELEGATED;

    private final JdbcTemplate jdbc;

    JdbcMembershipStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pessoa da equipe da unidade, com o vínculo (ativo ou desativado) e as delegações vigentes. */
    public record MemberRow(UUID membershipId, UUID identityId, String displayName, String email, MembershipRole role,
            boolean active, Instant createdAt, Instant deactivatedAt, List<Permission> delegated) {
    }

    /** Vínculo travado para uma mudança de equipe, lido depois do lock. */
    public record LockedMembership(UUID membershipId, UUID identityId, MembershipRole role, boolean active) {
    }

    public Optional<IdentitySummary> identity(UUID identityId) {
        return jdbc.query("SELECT id, display_name FROM user_identity WHERE id = ?",
                (rs, n) -> new IdentitySummary(rs.getObject("id", UUID.class), rs.getString("display_name")),
                identityId).stream().findFirst();
    }

    /** Contexto de identidade, sem unidade. Ordem estável por nome e id. */
    public List<UnitMembership> activeUnitsOfCurrentIdentity(UUID identityId) {
        return jdbc.query("SELECT " + UNIT_COLUMNS + """
                FROM membership m JOIN establishment e ON e.id = m.establishment_id
                WHERE m.identity_id = ? AND m.identity_id = current_identity_id() AND m.status = 'ACTIVE'
                ORDER BY lower(e.name), e.id
                """, JdbcMembershipStore::unit, identityId);
    }

    /** Contexto de unidade: vínculo ativo da identidade na unidade corrente. */
    public Optional<UnitMembership> activeMembershipInCurrentUnit(UUID identityId) {
        return jdbc.query("SELECT " + UNIT_COLUMNS + """
                FROM membership m JOIN establishment e ON e.id = m.establishment_id
                WHERE m.establishment_id = current_establishment_id() AND m.identity_id = ? AND m.status = 'ACTIVE'
                """, JdbcMembershipStore::unit, identityId).stream().findFirst();
    }

    /** Contexto de unidade: a equipe, ativos primeiro, por nome e id. */
    public List<MemberRow> members() {
        return jdbc.query("""
                SELECT m.id AS membership_id, m.identity_id, u.display_name, u.email, m.role, m.status, m.created_at,
                       m.deactivated_at,
                """ + DELEGATED + """
                FROM membership m JOIN user_identity u ON u.id = m.identity_id
                WHERE m.establishment_id = current_establishment_id()
                ORDER BY m.status = 'ACTIVE' DESC, lower(u.display_name), m.id
                """, (rs, n) -> new MemberRow(rs.getObject("membership_id", UUID.class),
                rs.getObject("identity_id", UUID.class), rs.getString("display_name"), rs.getString("email"),
                MembershipRole.valueOf(rs.getString("role")), "ACTIVE".equals(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "deactivated_at"), permissions(rs.getArray("delegated"))));
    }

    /** Contexto de unidade: alguém com este e-mail já participa da unidade? */
    public boolean activeMemberWithEmail(String email) {
        return !jdbc.queryForList("""
                SELECT 1 FROM membership m JOIN user_identity u ON u.id = m.identity_id
                 WHERE m.establishment_id = current_establishment_id() AND m.status = 'ACTIVE' AND u.email = ?
                """, Integer.class, email).isEmpty();
    }

    /**
     * Contexto de unidade: trava, em ordem de id, os vínculos pedidos e todos os administradores ativos, e devolve
     * como estão depois do lock. Mudanças de equipe simultâneas na mesma unidade esperam aqui; a regra do último
     * administrador e a autoridade de quem age são conferidas sobre estas linhas.
     */
    public List<LockedMembership> lockForTeamChange(List<UUID> membershipIds) {
        return jdbc.query("""
                SELECT id, identity_id, role, status FROM membership
                 WHERE establishment_id = current_establishment_id()
                   AND (id = ANY (?) OR (role = 'ADMIN_ESTABELECIMENTO' AND status = 'ACTIVE'))
                 ORDER BY id
                   FOR UPDATE
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", membershipIds.toArray())),
                (rs, n) -> new LockedMembership(rs.getObject("id", UUID.class), rs.getObject("identity_id", UUID.class),
                        MembershipRole.valueOf(rs.getString("role")), "ACTIVE".equals(rs.getString("status"))));
    }

    public boolean deactivate(UUID membershipId, UUID actorId) {
        return jdbc.update("""
                UPDATE membership
                   SET status = 'INACTIVE', deactivated_at = now(), deactivated_by = ?, version = version + 1
                 WHERE id = ? AND establishment_id = current_establishment_id() AND status = 'ACTIVE'
                """, actorId, membershipId) == 1;
    }

    /** Contexto de unidade: troca o papel de um vínculo ativo. */
    public boolean changeRole(UUID membershipId, MembershipRole role) {
        return jdbc.update("""
                UPDATE membership SET role = ?, version = version + 1
                 WHERE id = ? AND establishment_id = current_establishment_id() AND status = 'ACTIVE'
                """, role.name(), membershipId) == 1;
    }

    /** Contexto de unidade com a identidade de quem concede. */
    public void grant(UUID membershipId, Permission permission, UUID actorId, Instant at) {
        jdbc.update("""
                INSERT INTO membership_permission (id, establishment_id, membership_id, permission, granted_by, granted_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, UUID.randomUUID(), membershipId, permission.name(), actorId, Timestamp.from(at));
    }

    /** Contexto de unidade com a identidade de quem revoga; a linha fica como histórico. */
    public boolean revoke(UUID membershipId, Permission permission, UUID actorId, Instant at) {
        return jdbc.update("""
                UPDATE membership_permission SET revoked_at = ?, revoked_by = ?
                 WHERE establishment_id = current_establishment_id() AND membership_id = ? AND permission = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(at), actorId, membershipId, permission.name()) == 1;
    }

    /** Contexto de unidade: delegações vigentes do vínculo. */
    public List<Permission> delegations(UUID membershipId) {
        return jdbc.queryForList("""
                SELECT permission FROM membership_permission
                 WHERE establishment_id = current_establishment_id() AND membership_id = ? AND revoked_at IS NULL
                 ORDER BY permission
                """, String.class, membershipId).stream().map(Permission::valueOf).toList();
    }

    public void audit(String action, UUID subjectId, UUID actorId, String correlationId) {
        audit(action, subjectId, actorId, null, correlationId);
    }

    /** Auditoria na unidade do contexto; {@code detail} diz o que mudou, sem dado pessoal. */
    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static UnitMembership unit(ResultSet rs, int row) throws SQLException {
        MembershipRole role = MembershipRole.valueOf(rs.getString("role"));
        return new UnitMembership(rs.getObject("membership_id", UUID.class), rs.getObject("identity_id", UUID.class),
                rs.getObject("establishment_id", UUID.class), rs.getString("establishment_name"), role,
                AccessPolicy.effective(role, permissions(rs.getArray("delegated"))));
    }

    private static List<Permission> permissions(Array array) throws SQLException {
        List<Permission> result = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            result.add(Permission.valueOf((String) value));
        }
        return result;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
