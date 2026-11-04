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

    /** Linha enviada vista pela produção (H18): sem preço. */
    public record ProductionLineRow(UUID id, UUID orderId, int position, String productName, int quantity, String note,
            UUID sentBy, Instant sentAt) {
    }

    public record AllocationRow(UUID orderLineId, UUID tabId, int quantity) {
    }

    public List<ProductionLineRow> productionLines(List<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT l.id, l.order_id, l.position, l.product_name, l.quantity, l.note, o.sent_by, o.sent_at
                  FROM order_line l JOIN customer_order o ON o.id = l.order_id
                 WHERE l.id = ANY (?)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", lineIds.toArray())),
                (rs, n) -> new ProductionLineRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getInt("position"), rs.getString("product_name"), rs.getInt("quantity"), rs.getString("note"),
                        rs.getObject("sent_by", UUID.class), rs.getTimestamp("sent_at").toInstant()));
    }

    public List<AllocationRow> allocations(List<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT order_line_id, tab_id, quantity FROM tab_allocation
                 WHERE order_line_id = ANY (?) ORDER BY created_at, id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", lineIds.toArray())),
                (rs, n) -> new AllocationRow(rs.getObject("order_line_id", UUID.class),
                        rs.getObject("tab_id", UUID.class), rs.getInt("quantity")));
    }

    /** Unidades alocadas numa comanda, por linha enviada (H21: o que o consumidor pode apontar num problema). */
    public record TabItemRow(UUID orderLineId, String productName, int quantity, java.time.Instant sentAt) {
    }

    public List<TabItemRow> tabItems(UUID tabId) {
        return jdbc.query("""
                SELECT l.id, l.product_name, sum(a.quantity)::int AS quantity, o.sent_at, l.position
                  FROM tab_allocation a
                  JOIN order_line l ON l.establishment_id = a.establishment_id AND l.id = a.order_line_id
                  JOIN customer_order o ON o.establishment_id = l.establishment_id AND o.id = l.order_id
                 WHERE a.tab_id = ?
                 GROUP BY l.id, l.product_name, o.sent_at, l.position
                 ORDER BY o.sent_at, l.position
                """, (rs, n) -> new TabItemRow(rs.getObject("id", UUID.class), rs.getString("product_name"),
                rs.getInt("quantity"), rs.getTimestamp("sent_at").toInstant()), tabId);
    }

    /** Linha enviada com as unidades alocadas na comanda e o preço do envio (H22, composição da conta). */
    public record ChargedLineRow(UUID orderLineId, String productName, int quantity, BigDecimal unitPrice,
            Instant sentAt) {
    }

    public List<ChargedLineRow> chargedLines(UUID tabId) {
        return jdbc.query("""
                SELECT l.id, l.product_name, sum(a.quantity)::int AS quantity, l.unit_price, o.sent_at, l.position
                  FROM tab_allocation a
                  JOIN order_line l ON l.establishment_id = a.establishment_id AND l.id = a.order_line_id
                  JOIN customer_order o ON o.establishment_id = l.establishment_id AND o.id = l.order_id
                 WHERE a.tab_id = ?
                 GROUP BY l.id, l.product_name, l.unit_price, o.sent_at, l.position
                 ORDER BY o.sent_at, l.position, l.id
                """, (rs, n) -> new ChargedLineRow(rs.getObject("id", UUID.class), rs.getString("product_name"),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getTimestamp("sent_at").toInstant()),
                tabId);
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
