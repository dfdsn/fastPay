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

import tech.malyah.fastpay.attendance.CallKind;
import tech.malyah.fastpay.establishments.AttendanceMode;

/** Chamados de atendimento (H20), na unidade do contexto da transação (RLS). */
@Repository
public class JdbcAttendanceStore {

    /** Chamados por leitura da fila: os mais antigos primeiro. */
    public static final int PAGE = 200;

    private final JdbcTemplate jdbc;

    JdbcAttendanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CallRow(UUID id, UUID tabId, UUID lineId, CallKind kind, AttendanceMode mode, Instant createdAt,
            UUID claimedBy, Instant claimedAt, Instant concludedAt, long version) {
    }

    private static final String COLUMNS = """
            id, tab_id, production_line_id, kind, mode, created_at, claimed_by, claimed_at, concluded_at, version
            """;

    /**
     * Abre o aviso da linha para a comanda, se ainda não há um em aberto do mesmo tipo.
     *
     * @return {@code true} se criou agora
     */
    public boolean open(UUID tabId, UUID lineId, CallKind kind, AttendanceMode mode, Instant now) {
        return jdbc.update("""
                INSERT INTO attendance_call (id, establishment_id, tab_id, production_line_id, kind, mode, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                ON CONFLICT (production_line_id, tab_id, kind) WHERE concluded_at IS NULL DO NOTHING
                """, UUID.randomUUID(), tabId, lineId, kind.name(), mode.name(), Timestamp.from(now)) == 1;
    }

    /** Conclui os avisos em aberto da linha deste tipo. */
    public int conclude(UUID lineId, CallKind kind, String resolution, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE attendance_call
                   SET concluded_at = ?, concluded_by = ?, resolution = ?, version = version + 1
                 WHERE production_line_id = ? AND kind = ? AND concluded_at IS NULL
                """, Timestamp.from(now), actorId, resolution, lineId, kind.name());
    }

    /** Tipos dos avisos em aberto da linha (H25: seguem as unidades transferidas). */
    public List<CallKind> openKinds(UUID lineId) {
        return jdbc.queryForList("""
                SELECT DISTINCT kind FROM attendance_call WHERE production_line_id = ? AND concluded_at IS NULL
                 ORDER BY kind
                """, String.class, lineId).stream().map(CallKind::valueOf).toList();
    }

    /**
     * Conclui os avisos em aberto da linha nas comandas fora de {@code keep} (H25: ficaram sem unidades da linha).
     *
     * @return as comandas cujos avisos foram concluídos
     */
    public List<UUID> concludeOutside(UUID lineId, List<UUID> keep, String resolution, UUID actorId, Instant now) {
        return jdbc.query("""
                UPDATE attendance_call
                   SET concluded_at = ?, concluded_by = ?, resolution = ?, version = version + 1
                 WHERE production_line_id = ? AND concluded_at IS NULL AND NOT (tab_id = ANY (?))
                RETURNING tab_id
                """, ps -> {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setObject(2, actorId);
                    ps.setString(3, resolution);
                    ps.setObject(4, lineId);
                    ps.setArray(5, ps.getConnection().createArrayOf("uuid", keep.toArray()));
                }, (rs, n) -> rs.getObject("tab_id", UUID.class)).stream().distinct().toList();
    }

    /** Chamados em aberto, dos mais antigos aos mais novos. */
    public List<CallRow> open() {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM attendance_call WHERE concluded_at IS NULL
                 ORDER BY created_at, id LIMIT ?
                """, JdbcAttendanceStore::call, PAGE);
    }

    public Optional<CallRow> call(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attendance_call WHERE id = ?", JdbcAttendanceStore::call, id)
                .stream().findFirst();
    }

    /**
     * Trava o chamado em aberto: duas pessoas assumindo ao mesmo tempo passam uma de cada vez. O concluído não muda
     * mais (a policy de UPDATE não o alcança): volta lido, sem trava.
     */
    public Optional<CallRow> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attendance_call WHERE id = ? FOR UPDATE",
                JdbcAttendanceStore::call, id).stream().findFirst().or(() -> call(id));
    }

    public boolean assign(UUID id, long version, UUID assigneeId, Instant now) {
        return jdbc.update("""
                UPDATE attendance_call SET claimed_by = ?, claimed_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND concluded_at IS NULL
                """, assigneeId, Timestamp.from(now), id, version) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static CallRow call(ResultSet rs, int n) throws SQLException {
        return new CallRow(rs.getObject("id", UUID.class), rs.getObject("tab_id", UUID.class),
                rs.getObject("production_line_id", UUID.class), CallKind.valueOf(rs.getString("kind")),
                AttendanceMode.valueOf(rs.getString("mode")), instant(rs, "created_at"),
                rs.getObject("claimed_by", UUID.class), instant(rs, "claimed_at"), instant(rs, "concluded_at"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
