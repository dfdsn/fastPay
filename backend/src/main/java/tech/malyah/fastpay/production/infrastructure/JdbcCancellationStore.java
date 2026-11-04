package tech.malyah.fastpay.production.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.production.domain.ProductionRules.Status;

/** Cancelamentos de unidades (H23) e a ciência da produção, na unidade do contexto da transação (RLS). */
@Repository
public class JdbcCancellationStore {

    /** Cancelamentos pendentes de ciência por leitura: a tela mostra os mais antigos primeiro. */
    public static final int PAGE = 200;

    private final JdbcTemplate jdbc;

    JdbcCancellationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CancellationRow(UUID id, UUID orderLineId, UUID productionLineId, UUID tabId, int quantity,
            int remainingInTab, Status stage, String reason, UUID cancelledBy, Instant cancelledAt,
            boolean requiresAck, UUID acknowledgedBy, Instant acknowledgedAt) {
    }

    private static final String COLUMNS = """
            id, order_line_id, production_line_id, tab_id, quantity, remaining_in_tab, stage, reason, cancelled_by,
            cancelled_at, requires_ack, acknowledged_by, acknowledged_at
            """;

    public void insert(CancellationRow c, String correlationId) {
        jdbc.update("""
                INSERT INTO item_cancellation (id, establishment_id, order_line_id, production_line_id, tab_id,
                                               quantity, remaining_in_tab, stage, reason, cancelled_by, cancelled_at,
                                               requires_ack, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.orderLineId(), c.productionLineId(), c.tabId(), c.quantity(), c.remainingInTab(),
                c.stage().name(), c.reason(), c.cancelledBy(), Timestamp.from(c.cancelledAt()), c.requiresAck(),
                correlationId);
    }

    public Optional<CancellationRow> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM item_cancellation WHERE id = ?", JdbcCancellationStore::row,
                id).stream().findFirst();
    }

    /** Pendentes de ciência, dos mais antigos aos mais novos (setor filtrado por quem chama). */
    public List<CancellationRow> pending() {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM item_cancellation
                 WHERE requires_ack AND acknowledged_at IS NULL
                 ORDER BY cancelled_at, id
                 LIMIT ?
                """, JdbcCancellationStore::row, PAGE);
    }

    /** Cancelamentos das linhas, dos mais antigos aos mais novos (histórico na tela da comanda). */
    public List<CancellationRow> ofLines(List<UUID> orderLineIds) {
        if (orderLineIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM item_cancellation WHERE order_line_id = ANY (?) ORDER BY cancelled_at, id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", orderLineIds.toArray())),
                JdbcCancellationStore::row);
    }

    /**
     * Grava a ciência se ainda está pendente. Duas pessoas ao mesmo tempo: a segunda não muda nada.
     *
     * @return {@code true} se gravou agora
     */
    public boolean acknowledge(UUID id, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE item_cancellation SET acknowledged_by = ?, acknowledged_at = ?
                 WHERE id = ? AND requires_ack AND acknowledged_at IS NULL
                """, actorId, Timestamp.from(now), id) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static CancellationRow row(ResultSet rs, int n) throws SQLException {
        Timestamp ack = rs.getTimestamp("acknowledged_at");
        return new CancellationRow(rs.getObject("id", UUID.class), rs.getObject("order_line_id", UUID.class),
                rs.getObject("production_line_id", UUID.class), rs.getObject("tab_id", UUID.class),
                rs.getInt("quantity"), rs.getInt("remaining_in_tab"), Status.valueOf(rs.getString("stage")),
                rs.getString("reason"), rs.getObject("cancelled_by", UUID.class),
                rs.getTimestamp("cancelled_at").toInstant(), rs.getBoolean("requires_ack"),
                rs.getObject("acknowledged_by", UUID.class), ack == null ? null : ack.toInstant());
    }
}
