package tech.malyah.fastpay.production.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.production.domain.ProductionRules.Status;

/** Transferências de unidades entre comandas (H25), na unidade do contexto da transação (RLS). */
@Repository
public class JdbcTransferStore {

    private final JdbcTemplate jdbc;

    JdbcTransferStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TransferRow(UUID id, UUID orderLineId, UUID productionLineId, UUID fromTabId, UUID toTabId,
            int quantity, int remainingInFrom, Status stage, String reason, UUID transferredBy, Instant transferredAt) {
    }

    public void insert(TransferRow t, String correlationId) {
        jdbc.update("""
                INSERT INTO item_transfer (id, establishment_id, order_line_id, production_line_id, from_tab_id,
                                           to_tab_id, quantity, remaining_in_from, stage, reason, transferred_by,
                                           transferred_at, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, t.id(), t.orderLineId(), t.productionLineId(), t.fromTabId(), t.toTabId(), t.quantity(),
                t.remainingInFrom(), t.stage().name(), t.reason(), t.transferredBy(),
                Timestamp.from(t.transferredAt()), correlationId);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }
}
