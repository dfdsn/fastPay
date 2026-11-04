package tech.malyah.fastpay.tabs.infrastructure;

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
 * Comandas, visitas, QR de vínculo e acessos de consumidor (H15). Sempre no contexto da transação: unidade (RLS) ou,
 * para o consumidor ainda sem unidade, o hash apresentado.
 */
@Repository
public class JdbcTabStore {

    private final JdbcTemplate jdbc;

    JdbcTabStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code guestLinked}: há acesso de consumidor ativo (não revogado) na comanda. */
    public record TabRow(UUID id, UUID visitId, int number, String displayName, String tableLabel,
            UUID settingsVersionId, Instant openedAt, Instant closedAt, Instant accessUntil, boolean guestLinked,
            String attendanceMode, UUID responsibleId, long version) {
    }

    public record BindingRow(UUID id, UUID establishmentId, UUID tabId, boolean recovery, Instant expiresAt,
            Instant consumedAt, Instant invalidatedAt) {
    }

    public record GrantRow(UUID id, UUID establishmentId, UUID tabId, Instant revokedAt) {
    }

    // Comandas

    /** Serializa a escolha do número entre aberturas simultâneas da mesma unidade, até o fim da transação. */
    public void lockNumbering() {
        jdbc.queryForList("""
                SELECT pg_advisory_xact_lock(hashtextextended('tab-number:' || current_establishment_id()::text, 0))
                """);
    }

    /** Menor número livre entre as comandas ativas da unidade; vazio se todos de 1 a 9999 estão em uso. */
    public Optional<Integer> freeNumber() {
        return jdbc.query("""
                SELECT n FROM generate_series(1, 9999) AS n
                 WHERE NOT EXISTS (SELECT 1 FROM tab WHERE closed_at IS NULL AND number = n)
                 ORDER BY n LIMIT 1
                """, (rs, i) -> rs.getInt(1)).stream().findFirst();
    }

    public void insertVisit(UUID id, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO visit (id, establishment_id, started_by, started_at)
                VALUES (?, current_establishment_id(), ?, ?)
                """, id, actorId, Timestamp.from(now));
    }

    /** {@code responsibleId}: quem abre, na modalidade garçom responsável; nulo na central (H20). */
    public void insertTab(UUID id, UUID visitId, int number, String name, String table, UUID settingsVersionId,
            UUID responsibleId, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO tab (id, establishment_id, visit_id, number, display_name, table_label, settings_version_id,
                                 responsible_id, opened_by, opened_at, updated_by, updated_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, visitId, number, name, table, settingsVersionId, responsibleId, actorId, Timestamp.from(now),
                actorId, Timestamp.from(now));
    }

    private static final String TAB_COLUMNS = """
            t.id, t.visit_id, t.number, t.display_name, t.table_label, t.settings_version_id, t.opened_at,
            t.closed_at, t.access_until, t.version, t.responsible_id,
            (SELECT v.attendance_mode FROM unit_settings_version v WHERE v.id = t.settings_version_id)
                AS attendance_mode,
            EXISTS (SELECT 1 FROM guest_grant g WHERE g.tab_id = t.id AND g.revoked_at IS NULL) AS guest_linked
            """;

    /** Comandas ativas da unidade, por número. */
    public List<TabRow> activeTabs() {
        return jdbc.query("SELECT " + TAB_COLUMNS + " FROM tab t WHERE t.closed_at IS NULL ORDER BY t.number",
                JdbcTabStore::tab);
    }

    public Optional<TabRow> tab(UUID id) {
        return jdbc.query("SELECT " + TAB_COLUMNS + " FROM tab t WHERE t.id = ?", JdbcTabStore::tab, id).stream()
                .findFirst();
    }

    /** Trava a comanda ativa: QR, recuperação e troca de mesa da mesma comanda não se cruzam. */
    public Optional<TabRow> lockActiveTab(UUID id) {
        return jdbc.query("SELECT " + TAB_COLUMNS + " FROM tab t WHERE t.id = ? AND t.closed_at IS NULL FOR UPDATE OF t",
                JdbcTabStore::tab, id).stream().findFirst();
    }

    public boolean updateTable(UUID id, long version, String table, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE tab SET table_label = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND closed_at IS NULL
                """, table, actorId, Timestamp.from(now), id, version) == 1;
    }

    /** Troca o responsável da comanda ativa (H20). */
    public boolean updateResponsible(UUID id, long version, UUID responsibleId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE tab SET responsible_id = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND closed_at IS NULL
                """, responsibleId, actorId, Timestamp.from(now), id, version) == 1;
    }

    // QR de vínculo

    /** Invalida o QR em aberto da comanda (gerar outro substitui o anterior, FR-08). */
    public int invalidateOpenBindings(UUID tabId, Instant now) {
        return jdbc.update("""
                UPDATE binding_token SET invalidated_at = ?
                 WHERE tab_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(now), tabId);
    }

    public void insertBinding(UUID id, UUID tabId, String tokenHash, boolean recovery, UUID actorId, Instant now,
            Instant expiresAt) {
        jdbc.update("""
                INSERT INTO binding_token (id, establishment_id, tab_id, token_hash, recovery, created_by, created_at,
                                           expires_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?)
                """, id, tabId, tokenHash, recovery, actorId, Timestamp.from(now), Timestamp.from(expiresAt));
    }

    /** QR pelo hash, no contexto do hash apresentado ou da unidade. */
    public Optional<BindingRow> binding(String tokenHash) {
        return jdbc.query("""
                SELECT id, establishment_id, tab_id, recovery, expires_at, consumed_at, invalidated_at
                  FROM binding_token WHERE token_hash = ?
                """, JdbcTabStore::binding, tokenHash).stream().findFirst();
    }

    /** Trava o QR ainda em aberto (consumido ou invalidado não aparece). */
    public Optional<BindingRow> lockOpenBinding(String tokenHash) {
        return jdbc.query("""
                SELECT id, establishment_id, tab_id, recovery, expires_at, consumed_at, invalidated_at
                  FROM binding_token WHERE token_hash = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                   FOR UPDATE
                """, JdbcTabStore::binding, tokenHash).stream().findFirst();
    }

    public boolean consumeBinding(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE binding_token SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(now), id) == 1;
    }

    // Acesso do consumidor

    public void insertGrant(UUID id, UUID tabId, UUID bindingId, String tokenHash, Instant now) {
        jdbc.update("""
                INSERT INTO guest_grant (id, establishment_id, tab_id, binding_token_id, token_hash, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, id, tabId, bindingId, tokenHash, Timestamp.from(now));
    }

    public Optional<UUID> activeGrant(UUID tabId) {
        return jdbc.query("SELECT id FROM guest_grant WHERE tab_id = ? AND revoked_at IS NULL FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), tabId).stream().findFirst();
    }

    /** Acesso pelo hash, no contexto do hash apresentado ou da unidade. */
    public Optional<GrantRow> grant(String tokenHash) {
        return jdbc.query("SELECT id, establishment_id, tab_id, revoked_at FROM guest_grant WHERE token_hash = ?",
                (rs, n) -> new GrantRow(rs.getObject("id", UUID.class), rs.getObject("establishment_id", UUID.class),
                        rs.getObject("tab_id", UUID.class), instant(rs, "revoked_at")), tokenHash)
                .stream().findFirst();
    }

    public boolean revokeGrant(UUID id, String reason, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE guest_grant SET revoked_at = ?, revoked_by = ?, revoke_reason = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, Timestamp.from(now), actorId, reason, id) == 1;
    }

    public void insertRecovery(UUID id, UUID tabId, UUID revokedGrantId, UUID bindingId, UUID actorId, Instant now,
            String accountCheck, String reason) {
        jdbc.update("""
                INSERT INTO access_recovery (id, establishment_id, tab_id, revoked_grant_id, binding_token_id,
                                             performed_by, performed_at, name_checked, account_check, reason)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, true, ?, ?)
                """, id, tabId, revokedGrantId, bindingId, actorId, Timestamp.from(now), accountCheck, reason);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    /** Identificação da comanda para a produção e a entrega (H18). */
    public record LabelRow(UUID id, int number, String displayName, String tableLabel, UUID openedBy,
            String attendanceMode, UUID responsibleId) {
    }

    public List<LabelRow> labels(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT t.id, t.number, t.display_name, t.table_label, t.opened_by, t.responsible_id, v.attendance_mode
                  FROM tab t JOIN unit_settings_version v ON v.id = t.settings_version_id
                 WHERE t.id = ANY (?)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (rs, n) -> new LabelRow(rs.getObject("id", UUID.class), rs.getInt("number"),
                        rs.getString("display_name"), rs.getString("table_label"),
                        rs.getObject("opened_by", UUID.class), rs.getString("attendance_mode"),
                        rs.getObject("responsible_id", UUID.class)));
    }

    private static TabRow tab(ResultSet rs, int n) throws SQLException {
        return new TabRow(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class), rs.getInt("number"),
                rs.getString("display_name"), rs.getString("table_label"),
                rs.getObject("settings_version_id", UUID.class), instant(rs, "opened_at"), instant(rs, "closed_at"),
                instant(rs, "access_until"), rs.getBoolean("guest_linked"), rs.getString("attendance_mode"),
                rs.getObject("responsible_id", UUID.class), rs.getLong("version"));
    }

    private static BindingRow binding(ResultSet rs, int n) throws SQLException {
        return new BindingRow(rs.getObject("id", UUID.class), rs.getObject("establishment_id", UUID.class),
                rs.getObject("tab_id", UUID.class), rs.getBoolean("recovery"), instant(rs, "expires_at"),
                instant(rs, "consumed_at"), instant(rs, "invalidated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
