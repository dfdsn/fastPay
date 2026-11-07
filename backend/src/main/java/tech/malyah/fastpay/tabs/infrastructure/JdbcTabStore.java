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

    /**
     * {@code guestLinked}: há acesso de consumidor ativo (não revogado) na comanda. {@code continuesTabId}: a comanda
     * anterior cuja visita esta continua (H26), com o número e o nome dela; nulos sem vínculo.
     */
    public record TabRow(UUID id, UUID visitId, int number, String displayName, String tableLabel,
            UUID settingsVersionId, Instant openedAt, Instant closedAt, Instant accessUntil, boolean guestLinked,
            String attendanceMode, UUID responsibleId, long version, UUID continuesTabId, Integer continuesNumber,
            String continuesName, UUID closingId, Instant closingStartedAt) {
    }

    /** Visita (H26): {@code exitValidatedAt} gravado pela saída validada (H32). */
    public record VisitRow(UUID id, Instant exitValidatedAt) {
    }

    /** Comanda que pode ser a anterior de uma continuação de visita (H26). */
    public record CandidateRow(UUID id, int number, String displayName, String tableLabel, Instant openedAt,
            boolean closed) {
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
            EXISTS (SELECT 1 FROM guest_grant g WHERE g.tab_id = t.id AND g.revoked_at IS NULL) AS guest_linked,
            (SELECT l.previous_tab_id FROM visit_link l WHERE l.tab_id = t.id) AS continues_tab_id,
            (SELECT p.number FROM visit_link l JOIN tab p ON p.id = l.previous_tab_id WHERE l.tab_id = t.id)
                AS continues_number,
            (SELECT p.display_name FROM visit_link l JOIN tab p ON p.id = l.previous_tab_id WHERE l.tab_id = t.id)
                AS continues_name,
            (SELECT c.id FROM tab_closing c WHERE c.tab_id = t.id AND c.reopened_at IS NULL) AS closing_id,
            (SELECT c.started_at FROM tab_closing c WHERE c.tab_id = t.id AND c.reopened_at IS NULL)
                AS closing_started_at
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

    /**
     * Trava a comanda ativa: QR, recuperação, troca de mesa, envio, fechamento (H28) e as correções da mesma comanda
     * não se cruzam. A leitura vem num comando separado, depois da trava: em READ COMMITTED, quem esperou pela trava
     * enxerga o que quem a segurava gravou em outras tabelas (o fechamento iniciado, por exemplo); as subconsultas do
     * próprio {@code SELECT ... FOR UPDATE} usariam a foto de antes da espera.
     */
    public Optional<TabRow> lockActiveTab(UUID id) {
        boolean locked = !jdbc.query("SELECT t.id FROM tab t WHERE t.id = ? AND t.closed_at IS NULL FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), id).isEmpty();
        return locked ? tab(id) : Optional.empty();
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

    // Visitas (H26)

    /** Trava a visita até o fim da transação: vínculo, couvert e saída da mesma visita não se cruzam. */
    public Optional<VisitRow> lockVisit(UUID id) {
        return jdbc.query("SELECT id, exit_validated_at FROM visit WHERE id = ? FOR UPDATE",
                (rs, n) -> new VisitRow(rs.getObject("id", UUID.class), instant(rs, "exit_validated_at")), id)
                .stream().findFirst();
    }

    /** Comandas da visita (ativas e encerradas), da abertura mais antiga à mais nova. */
    public List<UUID> tabsOfVisit(UUID visitId) {
        return jdbc.query("SELECT id FROM tab WHERE visit_id = ? ORDER BY opened_at, id",
                (rs, n) -> rs.getObject(1, UUID.class), visitId);
    }

    /**
     * Comandas que podem ser a anterior de {@code tabId}: abertas desde {@code since} e até a abertura dela, de outra
     * visita, sem saída validada; da mais recente à mais antiga, sem ordenar nem filtrar por nome (FR-12).
     */
    public List<CandidateRow> continuationCandidates(UUID tabId, UUID visitId, Instant openedAt, Instant since,
            int limit) {
        return jdbc.query("""
                SELECT t.id, t.number, t.display_name, t.table_label, t.opened_at, t.closed_at IS NOT NULL AS closed
                  FROM tab t JOIN visit v ON v.id = t.visit_id
                 WHERE t.id <> ? AND t.visit_id <> ? AND t.opened_at <= ? AND t.opened_at >= ?
                   AND v.exit_validated_at IS NULL
                 ORDER BY t.opened_at DESC, t.number
                 LIMIT ?
                """, (rs, n) -> new CandidateRow(rs.getObject("id", UUID.class), rs.getInt("number"),
                        rs.getString("display_name"), rs.getString("table_label"), instant(rs, "opened_at"),
                        rs.getBoolean("closed")),
                tabId, visitId, Timestamp.from(openedAt), Timestamp.from(since), limit);
    }

    /** Leva a comanda ativa para a visita {@code visitId} (continuação, H26). */
    public boolean moveToVisit(UUID tabId, long version, UUID visitId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE tab SET visit_id = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND closed_at IS NULL
                """, visitId, actorId, Timestamp.from(now), tabId, version) == 1;
    }

    public void insertVisitLink(UUID id, UUID tabId, UUID previousTabId, UUID fromVisitId, UUID toVisitId,
            UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO visit_link (id, establishment_id, tab_id, previous_tab_id, from_visit_id, to_visit_id,
                                        stay_confirmed, linked_by, linked_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, true, ?, ?)
                """, id, tabId, previousTabId, fromVisitId, toVisitId, actorId, Timestamp.from(now));
    }

    /** Saída validada (H32) encerra a visita; {@code false} se já estava encerrada. */
    public boolean endVisitByExit(UUID visitId, UUID tabId, Instant now) {
        return jdbc.update("""
                UPDATE visit SET exit_validated_at = ?, exit_tab_id = ? WHERE id = ? AND exit_validated_at IS NULL
                """, Timestamp.from(now), tabId, visitId) == 1;
    }

    // Fechamento (H28)

    /** Inicia o fechamento da comanda ativa, já travada por quem chama: a equipe ({@code actorId}) ou o cliente. */
    public void insertClosing(UUID id, UUID tabId, UUID actorId, UUID grantId, Instant now) {
        jdbc.update("""
                INSERT INTO tab_closing (id, establishment_id, tab_id, started_at, started_by, started_by_grant)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, id, tabId, Timestamp.from(now), actorId, grantId);
    }

    /** Reabre: o fechamento em andamento termina com quem, quando e o motivo; {@code false} se já não estava. */
    public boolean reopenClosing(UUID id, UUID actorId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE tab_closing SET reopened_at = ?, reopened_by = ?, reopen_reason = ?
                 WHERE id = ? AND reopened_at IS NULL
                """, Timestamp.from(now), actorId, reason, id) == 1;
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
            String attendanceMode, UUID responsibleId, boolean closed) {
    }

    public List<LabelRow> labels(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT t.id, t.number, t.display_name, t.table_label, t.opened_by, t.responsible_id, v.attendance_mode,
                       t.closed_at IS NOT NULL AS closed
                  FROM tab t JOIN unit_settings_version v ON v.id = t.settings_version_id
                 WHERE t.id = ANY (?)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (rs, n) -> new LabelRow(rs.getObject("id", UUID.class), rs.getInt("number"),
                        rs.getString("display_name"), rs.getString("table_label"),
                        rs.getObject("opened_by", UUID.class), rs.getString("attendance_mode"),
                        rs.getObject("responsible_id", UUID.class), rs.getBoolean("closed")));
    }

    private static TabRow tab(ResultSet rs, int n) throws SQLException {
        return new TabRow(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class), rs.getInt("number"),
                rs.getString("display_name"), rs.getString("table_label"),
                rs.getObject("settings_version_id", UUID.class), instant(rs, "opened_at"), instant(rs, "closed_at"),
                instant(rs, "access_until"), rs.getBoolean("guest_linked"), rs.getString("attendance_mode"),
                rs.getObject("responsible_id", UUID.class), rs.getLong("version"),
                rs.getObject("continues_tab_id", UUID.class), (Integer) rs.getObject("continues_number"),
                rs.getString("continues_name"), rs.getObject("closing_id", UUID.class),
                instant(rs, "closing_started_at"));
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
