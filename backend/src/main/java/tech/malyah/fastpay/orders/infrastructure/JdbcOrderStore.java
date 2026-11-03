package tech.malyah.fastpay.orders.infrastructure;

import java.math.BigDecimal;
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

/** Pedidos enviados, linhas e alocações (H16), sempre na unidade do contexto da transação (RLS). */
@Repository
public class JdbcOrderStore {

    private final JdbcTemplate jdbc;

    JdbcOrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OrderRow(UUID id, UUID tabId, UUID operationKey, String payloadHash, UUID sentBy, Instant sentAt) {
    }

    public record LineRow(UUID id, UUID orderId, int position, UUID productId, String productName,
            BigDecimal unitPrice, int quantity, String note, UUID sectorId) {
    }

    private static final String ORDER_COLUMNS = "id, tab_id, operation_key, payload_hash, sent_by, sent_at";

    public Optional<OrderRow> byOperationKey(UUID operationKey) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE operation_key = ?",
                JdbcOrderStore::order, operationKey).stream().findFirst();
    }

    public Optional<OrderRow> order(UUID id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE id = ?", JdbcOrderStore::order, id)
                .stream().findFirst();
    }

    /** Pedidos da comanda, do mais antigo ao mais novo. */
    public List<OrderRow> ofTab(UUID tabId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM customer_order WHERE tab_id = ? ORDER BY sent_at, id",
                JdbcOrderStore::order, tabId);
    }

    /** Linhas dos pedidos, por pedido e posição. */
    public List<LineRow> lines(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, order_id, position, product_id, product_name, unit_price, quantity, note, sector_id
                  FROM order_line WHERE order_id = ANY (?) ORDER BY order_id, position
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", orderIds.toArray())),
                JdbcOrderStore::line);
    }

    public void insertOrder(UUID id, UUID tabId, UUID operationKey, String payloadHash, int lineCount, UUID actorId,
            Instant now, String correlationId) {
        jdbc.update("""
                INSERT INTO customer_order (id, establishment_id, tab_id, operation_key, payload_hash, line_count,
                                            sent_by, sent_at, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?)
                """, id, tabId, operationKey, payloadHash, lineCount, actorId, Timestamp.from(now), correlationId);
    }

    /** Grava as linhas e a alocação de cada uma inteira na comanda do pedido. */
    public void insertLines(List<LineRow> lines, UUID tabId, Instant now) {
        List<Object[]> lineArgs = new ArrayList<>(lines.size());
        List<Object[]> allocationArgs = new ArrayList<>(lines.size());
        for (LineRow l : lines) {
            lineArgs.add(new Object[] { l.id(), l.orderId(), l.position(), l.productId(), l.productName(),
                    l.unitPrice(), l.quantity(), l.note(), l.sectorId() });
            allocationArgs.add(new Object[] { UUID.randomUUID(), l.id(), tabId, l.quantity(), Timestamp.from(now) });
        }
        jdbc.batchUpdate("""
                INSERT INTO order_line (id, establishment_id, order_id, position, product_id, product_name, unit_price,
                                        quantity, note, sector_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?)
                """, lineArgs);
        jdbc.batchUpdate("""
                INSERT INTO tab_allocation (id, establishment_id, order_line_id, tab_id, quantity, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, allocationArgs);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static OrderRow order(ResultSet rs, int n) throws SQLException {
        return new OrderRow(rs.getObject("id", UUID.class), rs.getObject("tab_id", UUID.class),
                rs.getObject("operation_key", UUID.class), rs.getString("payload_hash"),
                rs.getObject("sent_by", UUID.class), rs.getTimestamp("sent_at").toInstant());
    }

    private static LineRow line(ResultSet rs, int n) throws SQLException {
        return new LineRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getInt("position"), rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                rs.getBigDecimal("unit_price"), rs.getInt("quantity"), rs.getString("note"),
                rs.getObject("sector_id", UUID.class));
    }
}
