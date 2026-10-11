package tech.malyah.fastpay.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.UnitMembership;

/**
 * Acesso às tabelas {@code user_identity} e {@code membership}. Todas as consultas rodam sob RLS com o
 * contexto já definido pela transação ({@code TenantTransactions}); os filtros abaixo são a regra, as
 * policies são a garantia.
 */
@Repository
public class JdbcMembershipStore {

    private static final String UNIT_COLUMNS = """
            m.id AS membership_id, m.identity_id, m.establishment_id, e.name AS establishment_name, m.role
            """;

    private final JdbcTemplate jdbc;

    JdbcMembershipStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public boolean deactivate(UUID membershipId, UUID actorId) {
        return jdbc.update("""
                UPDATE membership
                   SET status = 'INACTIVE', deactivated_at = now(), deactivated_by = ?, version = version + 1
                 WHERE id = ? AND establishment_id = current_establishment_id() AND status = 'ACTIVE'
                """, actorId, membershipId) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, correlationId);
    }

    private static UnitMembership unit(ResultSet rs, int row) throws SQLException {
        return new UnitMembership(rs.getObject("membership_id", UUID.class), rs.getObject("identity_id", UUID.class),
                rs.getObject("establishment_id", UUID.class), rs.getString("establishment_name"),
                MembershipRole.valueOf(rs.getString("role")));
    }
}
