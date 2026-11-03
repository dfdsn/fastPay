package tech.malyah.fastpay.establishments.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.establishments.CouvertMode;
import tech.malyah.fastpay.establishments.ExitMode;
import tech.malyah.fastpay.establishments.ServiceBase;
import tech.malyah.fastpay.establishments.SettingsValues;
import tech.malyah.fastpay.establishments.SettingsVersion;
import tech.malyah.fastpay.establishments.domain.CalendarRules.Calendar;

/**
 * Versões da configuração e calendários operacionais da unidade do contexto (H14), sob RLS. Versões só são lidas e
 * inseridas; calendários pendentes podem ser cancelados.
 */
@Repository
public class JdbcSettingsStore {

    private static final String VERSION_COLUMNS = """
            v.id, v.number, v.service_enabled, v.service_percent, v.service_base, v.service_includes_couvert,
            v.couvert_enabled, v.couvert_mode, v.couvert_amount, v.discount_fixed_allowed, v.discount_percent_allowed,
            v.attendance_mode, v.exit_mode, v.created_by, v.created_at, u.display_name AS author
            """;

    private final JdbcTemplate jdbc;

    JdbcSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Versão com o nome de quem salvou (nulo no padrão inicial). */
    public record VersionRow(SettingsVersion version, String author) {
    }

    /** Trava a unidade do contexto para salvar a configuração (uma mudança por vez). */
    public boolean lockUnit() {
        return !jdbc.queryForList("SELECT id FROM establishment WHERE id = current_establishment_id() FOR UPDATE",
                UUID.class).isEmpty();
    }

    public Optional<SettingsVersion> latest() {
        return recent(1).stream().findFirst().map(VersionRow::version);
    }

    /** Versões mais recentes primeiro. */
    public List<VersionRow> recent(int limit) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                  FROM unit_settings_version v LEFT JOIN user_identity u ON u.id = v.created_by
                 WHERE v.establishment_id = current_establishment_id()
                 ORDER BY v.number DESC
                 LIMIT ?
                """, JdbcSettingsStore::versionRow, limit);
    }

    public Optional<SettingsVersion> version(UUID id) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                  FROM unit_settings_version v LEFT JOIN user_identity u ON u.id = v.created_by
                 WHERE v.establishment_id = current_establishment_id() AND v.id = ?
                """, JdbcSettingsStore::versionRow, id).stream().findFirst().map(VersionRow::version);
    }

    public UUID insert(int number, SettingsValues values, UUID actorId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO unit_settings_version (id, establishment_id, number, service_enabled, service_percent,
                       service_base, service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                       discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode, created_by,
                       created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, number, values.serviceEnabled(), values.servicePercent(), values.serviceBase().name(),
                values.serviceIncludesCouvert(), values.couvertEnabled(), values.couvertMode().name(),
                values.couvertAmount(), values.discountFixedAllowed(), values.discountPercentAllowed(),
                values.attendanceMode().name(), values.exitMode().name(), actorId, Timestamp.from(now));
        return id;
    }

    /** Calendários não cancelados, do mais antigo ao mais novo. */
    public List<Calendar> calendars() {
        return jdbc.query("""
                SELECT id, time_zone, cutoff, effective_from, first_date FROM operational_calendar
                 WHERE establishment_id = current_establishment_id() AND canceled_at IS NULL
                 ORDER BY effective_from
                """, (rs, n) -> new Calendar(rs.getObject("id", UUID.class), ZoneId.of(rs.getString("time_zone")),
                rs.getObject("cutoff", LocalTime.class), rs.getTimestamp("effective_from").toInstant(),
                rs.getObject("first_date", LocalDate.class)));
    }

    public UUID insertCalendar(ZoneId zone, LocalTime cutoff, Instant effectiveFrom, LocalDate firstDate,
            UUID actorId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO operational_calendar (id, establishment_id, time_zone, cutoff, effective_from, first_date,
                       created_by, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?)
                """, id, zone.getId(), cutoff, Timestamp.from(effectiveFrom), firstDate, actorId,
                Timestamp.from(now));
        return id;
    }

    /** Cancela a mudança de calendário que ainda não começou a valer. */
    public boolean cancel(UUID calendarId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE operational_calendar SET canceled_at = ?, canceled_by = ?
                 WHERE establishment_id = current_establishment_id() AND id = ? AND canceled_at IS NULL
                   AND effective_from > ?
                """, Timestamp.from(now), actorId, calendarId, Timestamp.from(now)) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static VersionRow versionRow(ResultSet rs, int n) throws SQLException {
        SettingsValues values = new SettingsValues(rs.getBoolean("service_enabled"),
                rs.getBigDecimal("service_percent"), ServiceBase.valueOf(rs.getString("service_base")),
                rs.getBoolean("service_includes_couvert"), rs.getBoolean("couvert_enabled"),
                CouvertMode.valueOf(rs.getString("couvert_mode")), rs.getBigDecimal("couvert_amount"),
                rs.getBoolean("discount_fixed_allowed"), rs.getBoolean("discount_percent_allowed"),
                AttendanceMode.valueOf(rs.getString("attendance_mode")), ExitMode.valueOf(rs.getString("exit_mode")));
        return new VersionRow(new SettingsVersion(rs.getObject("id", UUID.class), rs.getInt("number"), values,
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant()),
                rs.getString("author"));
    }
}
