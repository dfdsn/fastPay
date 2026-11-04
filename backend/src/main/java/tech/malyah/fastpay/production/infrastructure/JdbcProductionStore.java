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

    private final JdbcTemplate jdbc;

    JdbcProductionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LineRow(UUID id, UUID orderLineId, UUID sectorId, int quantity, Status status, Instant receivedAt,
            Instant statusSince, Instant startedAt, Instant readyAt, Instant deliveredAt, Instant impossibleAt,
            String impossibleReason, long version) {

        public boolean impossible() {
            return impossibleAt != null;
        }
    }

    private static final String COLUMNS = """
            id, order_line_id, sector_id, quantity, status, received_at, status_since, started_at, ready_at,
            delivered_at, impossible_at, impossible_reason, version
            """;

    /**
     * Cria a sequência de produção da linha se ainda não existe: sem setor entra pronta. Repetir não duplica.
     *
     * @return {@code true} se criou agora
     */
    public boolean receive(UUID orderLineId, UUID sectorId, int quantity, Instant now) {
        Status status = ProductionRules.initial(sectorId != null);
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO production_line (id, establishment_id, order_line_id, sector_id, quantity, status,
                                             received_at, status_since, ready_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_line_id) DO NOTHING
                """, UUID.randomUUID(), orderLineId, sectorId, quantity, status.name(), at, at,
                status == Status.PRONTO ? at : null) == 1;
    }

    /** Fila da produção: aguardando e em preparo, com setor (todos ou um), das mais antigas às mais novas. */
    public List<LineRow> queue(UUID sectorId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM production_line
                 WHERE status IN ('AGUARDANDO_PREPARO', 'EM_PREPARO') AND sector_id IS NOT NULL
                   AND (?::uuid IS NULL OR sector_id = ?::uuid)
                 ORDER BY received_at, id
                 LIMIT ?
                """, JdbcProductionStore::line, sectorId, sectorId, PAGE);
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

    public Optional<LineRow> line(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM production_line WHERE id = ?", JdbcProductionStore::line, id)
                .stream().findFirst();
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
            case AGUARDANDO_PREPARO -> throw new IllegalArgumentException("Etapa inicial não é avanço.");
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
        jdbc.update("""
                INSERT INTO production_event (id, establishment_id, production_line_id, action, from_status,
                                              to_status, reason, line_version, performed_by, performed_at,
                                              correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), lineId, action.name(), from.name(), to.name(), reason, lineVersion, actorId,
                Timestamp.from(now), correlationId);
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
                rs.getString("impossible_reason"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
