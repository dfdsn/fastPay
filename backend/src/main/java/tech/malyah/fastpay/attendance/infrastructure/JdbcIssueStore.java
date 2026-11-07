package tech.malyah.fastpay.attendance.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.attendance.domain.IssueRules.Correction;
import tech.malyah.fastpay.attendance.domain.IssueRules.Status;
import tech.malyah.fastpay.establishments.AttendanceMode;

/** Problemas do consumidor (H21), na unidade do contexto da transação (RLS). */
@Repository
public class JdbcIssueStore {

    /** Problemas por leitura da fila ou da comanda. */
    public static final int PAGE = 200;

    private final JdbcTemplate jdbc;

    JdbcIssueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record IssueRow(UUID id, UUID tabId, UUID orderLineId, String description, AttendanceMode mode,
            boolean afterClosure, Instant createdAt, Status status, UUID attendedBy, Instant attendedAt,
            Instant forwardedAt, UUID forwardedBy, Instant concludedAt, UUID concludedBy, String resolution,
            Correction correction, long version, boolean aboutCouvert) {
    }

    private static final String COLUMNS = """
            id, tab_id, order_line_id, description, mode, after_closure, created_at, status, attended_by, attended_at,
            forwarded_at, forwarded_by, concluded_at, concluded_by, resolution, correction, version, about_couvert
            """;

    /**
     * Grava o problema aberto pelo consumidor.
     *
     * @return {@code false} se já há um em aberto para o mesmo item (ou para a conta) desta comanda
     */
    public boolean insert(UUID id, UUID tabId, UUID orderLineId, boolean aboutCouvert, String description,
            AttendanceMode mode, boolean afterClosure, UUID grantId, Instant now) {
        String conflict = orderLineId == null
                ? "ON CONFLICT (tab_id) WHERE status <> 'TRATADO' AND order_line_id IS NULL DO NOTHING"
                : "ON CONFLICT (tab_id, order_line_id) WHERE status <> 'TRATADO' AND order_line_id IS NOT NULL "
                        + "DO NOTHING";
        return jdbc.update("""
                INSERT INTO consumer_issue (id, establishment_id, tab_id, order_line_id, about_couvert, description,
                                            mode, after_closure, created_grant_id, created_at, forwarded_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + conflict, id, tabId, orderLineId, aboutCouvert, description, mode.name(), afterClosure, grantId,
                Timestamp.from(now), afterClosure ? Timestamp.from(now) : null) == 1;
    }

    /** Problemas ainda não tratados da unidade, dos mais antigos aos mais novos. */
    public List<IssueRow> pending() {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM consumer_issue WHERE status <> 'TRATADO'
                 ORDER BY created_at, id LIMIT ?
                """, JdbcIssueStore::issue, PAGE);
    }

    /** Problemas da comanda (todos os estados), dos mais novos aos mais antigos. */
    public List<IssueRow> ofTab(UUID tabId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM consumer_issue WHERE tab_id = ?
                 ORDER BY created_at DESC, id LIMIT ?
                """, JdbcIssueStore::issue, tabId, PAGE);
    }

    public Optional<IssueRow> issue(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM consumer_issue WHERE id = ?", JdbcIssueStore::issue, id)
                .stream().findFirst();
    }

    /** Trava o problema ainda não tratado; o tratado (fora da policy de UPDATE) volta lido, sem trava. */
    public Optional<IssueRow> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM consumer_issue WHERE id = ? FOR UPDATE",
                JdbcIssueStore::issue, id).stream().findFirst().or(() -> issue(id));
    }

    public void attend(UUID id, long version, UUID attendantId, Instant now) {
        jdbc.update("""
                UPDATE consumer_issue SET status = 'EM_ATENDIMENTO', attended_by = ?, attended_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ? AND status <> 'TRATADO'
                """, attendantId, Timestamp.from(now), id, version);
    }

    public void forward(UUID id, long version, UUID actorId, Instant now) {
        jdbc.update("""
                UPDATE consumer_issue SET forwarded_at = ?, forwarded_by = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'EM_ATENDIMENTO'
                """, Timestamp.from(now), actorId, id, version);
    }

    public void conclude(UUID id, long version, UUID actorId, String resolution, Correction correction,
            Instant now) {
        jdbc.update("""
                UPDATE consumer_issue SET status = 'TRATADO', concluded_at = ?, concluded_by = ?, resolution = ?,
                       correction = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'EM_ATENDIMENTO'
                """, Timestamp.from(now), actorId, resolution, correction.name(), id, version);
    }

    /** A comanda tem problema ainda não tratado (FR-24, CA-14: bloqueia nova cobrança). */
    public boolean anyPending(UUID tabId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consumer_issue WHERE tab_id = ? AND status <> 'TRATADO')
                """, Boolean.class, tabId));
    }

    /**
     * Problema não tratado da comanda que impede transferir o item (H25): {@code true} se da conta, {@code false} se do
     * item; vazio se nenhum. O do item vem primeiro.
     */
    public Optional<Boolean> pendingForTransfer(UUID tabId, UUID orderLineId) {
        return jdbc.queryForList("""
                SELECT order_line_id IS NULL FROM consumer_issue
                 WHERE tab_id = ? AND status <> 'TRATADO' AND (order_line_id IS NULL OR order_line_id = ?)
                 ORDER BY order_line_id IS NULL LIMIT 1
                """, Boolean.class, tabId, orderLineId).stream().findFirst();
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static IssueRow issue(ResultSet rs, int n) throws SQLException {
        String correction = rs.getString("correction");
        return new IssueRow(rs.getObject("id", UUID.class), rs.getObject("tab_id", UUID.class),
                rs.getObject("order_line_id", UUID.class), rs.getString("description"),
                AttendanceMode.valueOf(rs.getString("mode")), rs.getBoolean("after_closure"),
                instant(rs, "created_at"), Status.valueOf(rs.getString("status")),
                rs.getObject("attended_by", UUID.class), instant(rs, "attended_at"), instant(rs, "forwarded_at"),
                rs.getObject("forwarded_by", UUID.class), instant(rs, "concluded_at"),
                rs.getObject("concluded_by", UUID.class), rs.getString("resolution"),
                correction == null ? null : Correction.valueOf(correction), rs.getLong("version"),
                rs.getBoolean("about_couvert"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
