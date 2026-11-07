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

import tech.malyah.fastpay.production.domain.ProductionRules;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;

/** Linhas de produção (H16, H18) e o histórico das etapas, na unidade do contexto da transação (RLS). */
@Repository
public class JdbcProductionStore {

    /** Linhas por consulta de fila: a tela mostra as mais antigas primeiro. */
    public static final int PAGE = 200;

    /** Entregues recentes por consulta (H24): o que ainda costuma ser corrigido na hora. */
    public static final int DELIVERED_PAGE = 50;

    private final JdbcTemplate jdbc;

    JdbcProductionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LineRow(UUID id, UUID orderLineId, UUID sectorId, int quantity, Status status, Instant receivedAt,
            Instant statusSince, Instant startedAt, Instant readyAt, Instant deliveredAt, Instant impossibleAt,
            String impossibleReason, long version, int cancelledQuantity) {

        /** Unidades que ainda contam (H23): original menos as canceladas. */
        public int remaining() {
            return quantity - cancelledQuantity;
        }

        public boolean impossible() {
            return impossibleAt != null;
        }
    }

    private static final String COLUMNS = """
            id, order_line_id, sector_id, quantity, status, received_at, status_since, started_at, ready_at,
            delivered_at, impossible_at, impossible_reason, version, cancelled_quantity
            """;

    /**
     * Cria a sequência de produção da linha se ainda não existe: sem setor entra pronta. Repetir não duplica.
     *
     * @return {@code true} se criou agora
     */
    public boolean receive(UUID id, UUID orderLineId, UUID sectorId, int quantity, Instant now) {
        Status status = ProductionRules.initial(sectorId != null);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO production_line (id, establishment_id, order_line_id, sector_id, quantity, status,
                                             received_at, status_since, ready_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_line_id) DO NOTHING
                """, id, orderLineId, sectorId, quantity, status.name(), at, at,
                status == Status.PRONTO ? at : null) == 1;
    }

    /** Fila da produção: aguardando e em preparo, com setor (todos ou um), das mais antigas às mais novas. */
    /**
     * Unidades ativas da comanda que ainda não foram entregues (H28, FR-31): sem linha de produção ainda (encaminhamento
     * pendente) ou com ela aguardando, em preparo ou pronta, com impossibilidade aberta ou não. Por envio e posição.
     */
    public record PendingRow(UUID orderLineId, String productName, int quantity, Status status, boolean impossible) {
    }

    public List<PendingRow> pendingOfTab(UUID tabId) {
        return jdbc.query("""
                SELECT a.order_line_id, l.product_name, a.quantity, p.status, p.impossible_at IS NOT NULL AS impossible
                  FROM tab_allocation a
                  JOIN order_line l ON l.id = a.order_line_id
                  JOIN customer_order o ON o.id = l.order_id
                  LEFT JOIN production_line p ON p.order_line_id = a.order_line_id
                 WHERE a.tab_id = ? AND a.quantity > 0
                   AND (p.id IS NULL OR p.status NOT IN ('ENTREGUE', 'CANCELADA'))
                 ORDER BY o.sent_at, l.position, a.order_line_id
                """, (rs, n) -> new PendingRow(rs.getObject("order_line_id", UUID.class), rs.getString("product_name"),
                        rs.getInt("quantity"), rs.getString("status") == null ? null : Status.valueOf(rs.getString("status")),
                        rs.getBoolean("impossible")), tabId);
    }

    public List<LineRow> queue(UUID sectorId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM production_line
                 WHERE status IN ('AGUARDANDO_PREPARO', 'EM_PREPARO') AND sector_id IS NOT NULL
                   AND (?::uuid IS NULL OR sector_id = ?::uuid)
                 ORDER BY received_at, id
                 LIMIT ?
                """, JdbcProductionStore::line, sectorId, sectorId, PAGE);
    }

    /** Linhas pelo id (H20). */
    public List<LineRow> lines(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM production_line WHERE id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                JdbcProductionStore::line);
    }

    /** Entrega: prontas para entregar e linhas com impossibilidade aberta, das mais antigas às mais novas. */
    public List<LineRow> deliveries() {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM production_line
                 WHERE status = 'PRONTO' OR impossible_at IS NOT NULL
                 ORDER BY received_at, id
                 LIMIT ?
                """, JdbcProductionStore::line, PAGE);
    }

    /** Entregues mais recentes (H24), da entrega mais nova à mais antiga. */
    public List<LineRow> delivered() {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM production_line
                 WHERE status = 'ENTREGUE'
                 ORDER BY delivered_at DESC, id
                 LIMIT ?
                """, JdbcProductionStore::line, DELIVERED_PAGE);
    }

    /**
     * Desfaz a entrega da linha travada (H24): volta a pronta; o instante do pronto e os do preparo ficam, o da entrega
     * é limpo (a próxima entrega válida grava o seu).
     */
    public void undoDelivery(UUID id, long version, Instant now) {
        requireOne(jdbc.update("""
                UPDATE production_line SET status = 'PRONTO', status_since = ?, delivered_at = NULL,
                                           version = version + 1
                 WHERE id = ? AND version = ? AND status = 'ENTREGUE'
                """, Timestamp.from(now), id, version));
    }

    /** O passo ENTREGUE mais recente da linha: a entrega que vale até ser desfeita (H24). */
    public Optional<UUID> lastDelivery(UUID lineId) {
        return jdbc.query("""
                SELECT id FROM production_event
                 WHERE production_line_id = ? AND action = 'ENTREGUE'
                 ORDER BY line_version DESC
                 LIMIT 1
                """, (rs, n) -> rs.getObject("id", UUID.class), lineId).stream().findFirst();
    }

    public Optional<LineRow> line(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM production_line WHERE id = ?", JdbcProductionStore::line, id)
                .stream().findFirst();
    }

    /** Trava a linha da linha enviada (H23: cancelamento a partir da comanda). Vazio se a produção ainda não a recebeu. */
    public Optional<LineRow> lockByOrderLine(UUID orderLineId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM production_line WHERE order_line_id = ? FOR UPDATE",
                JdbcProductionStore::line, orderLineId).stream().findFirst();
    }

    /**
     * Grava um cancelamento na linha travada (H23): unidades canceladas, a etapa (CANCELADA quando não sobra nada antes
     * da entrega) e, se o cancelamento foi total, a sinalização de impossibilidade retirada.
     */
    public void cancel(UUID id, long version, int cancelledAfter, Status to, boolean clearImpossible, Instant now) {
        Timestamp at = Timestamp.from(now);
        requireOne(jdbc.update("""
                UPDATE production_line
                   SET cancelled_quantity = ?, cancelled_at = ?, status = ?,
                       status_since = CASE WHEN status = ? THEN status_since ELSE ? END,
                       impossible_at = CASE WHEN ? THEN NULL ELSE impossible_at END,
                       impossible_by = CASE WHEN ? THEN NULL ELSE impossible_by END,
                       impossible_reason = CASE WHEN ? THEN NULL ELSE impossible_reason END,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """, cancelledAfter, at, to.name(), to.name(), at, clearImpossible, clearImpossible,
                clearImpossible, id, version));
    }

    /**
     * Transferência de unidades na linha travada (H25): a etapa e os instantes ficam; só a versão sobe, para a entrega
     * com os destinos de antes ser recusada (H18).
     */
    public void transfer(UUID id, long version) {
        requireOne(jdbc.update("UPDATE production_line SET version = version + 1 WHERE id = ? AND version = ?", id,
                version));
    }

    /** Trava a linha até o fim da transação: duas mudanças de etapa simultâneas se enfileiram. */
    public Optional<LineRow> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM production_line WHERE id = ? FOR UPDATE",
                JdbcProductionStore::line, id).stream().findFirst();
    }

    /** Avança a etapa; o instante da etapa nova é gravado na coluna dela. */
    public void advance(UUID id, long version, Status to, Instant now) {
        String column = switch (to) {
            case EM_PREPARO -> "started_at";
            case PRONTO -> "ready_at";
            case ENTREGUE -> "delivered_at";
            case AGUARDANDO_PREPARO, CANCELADA -> throw new IllegalArgumentException("Não é avanço de etapa.");
        };
        Timestamp at = Timestamp.from(now);
        int updated = jdbc.update("UPDATE production_line SET status = ?, status_since = ?, " + column + """
                 = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, to.name(), at, at, id, version);
        requireOne(updated);
    }

    public void markImpossible(UUID id, long version, String reason, UUID actorId, Instant now) {
        requireOne(jdbc.update("""
                UPDATE production_line SET impossible_at = ?, impossible_by = ?, impossible_reason = ?,
                                           version = version + 1
                 WHERE id = ? AND version = ?
                """, Timestamp.from(now), actorId, reason, id, version));
    }

    public void clearImpossible(UUID id, long version) {
        requireOne(jdbc.update("""
                UPDATE production_line SET impossible_at = NULL, impossible_by = NULL, impossible_reason = NULL,
                                           version = version + 1
                 WHERE id = ? AND version = ?
                """, id, version));
    }

    public void event(UUID lineId, ProductionRules.Action action, Status from, Status to, String reason,
            long lineVersion, UUID actorId, Instant now, String correlationId) {
        event(UUID.randomUUID(), lineId, action, from, to, reason, lineVersion, null, actorId, now, correlationId);
    }

    /**
     * Passo do histórico com id escolhido por quem chama. {@code invalidates}: a entrega cuja amostra de tempo deixa de
     * valer (só no desfazer, H24).
     */
    public void event(UUID id, UUID lineId, ProductionRules.Action action, Status from, Status to, String reason,
            long lineVersion, UUID invalidates, UUID actorId, Instant now, String correlationId) {
        jdbc.update("""
                INSERT INTO production_event (id, establishment_id, production_line_id, action, from_status,
                                              to_status, reason, line_version, invalidates_event_id, performed_by,
                                              performed_at, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, lineId, action.name(), from.name(), to.name(), reason, lineVersion, invalidates, actorId,
                Timestamp.from(now), correlationId);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    /**
     * A ação já foi feita sobre a versão informada (H19): o histórico tem esse passo gravando a versão seguinte. Serve
     * para reconhecer a repetição de um lote, que não pode avançar uma segunda etapa.
     */
    public boolean applied(UUID lineId, ProductionRules.Action action, long expectedVersion) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM production_event
                                WHERE production_line_id = ? AND action = ? AND line_version = ?)
                """, Boolean.class, lineId, action.name(), expectedVersion + 1));
    }

    private static void requireOne(int updated) {
        // A linha está travada nesta transação: outra contagem é erro de programa, não corrida.
        if (updated != 1) {
            throw new IllegalStateException("Linha de produção travada não foi atualizada.");
        }
    }

    private static LineRow line(ResultSet rs, int n) throws SQLException {
        return new LineRow(rs.getObject("id", UUID.class), rs.getObject("order_line_id", UUID.class),
                rs.getObject("sector_id", UUID.class), rs.getInt("quantity"), Status.valueOf(rs.getString("status")),
                instant(rs, "received_at"), instant(rs, "status_since"), instant(rs, "started_at"),
                instant(rs, "ready_at"), instant(rs, "delivered_at"), instant(rs, "impossible_at"),
                rs.getString("impossible_reason"), rs.getLong("version"), rs.getInt("cancelled_quantity"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
