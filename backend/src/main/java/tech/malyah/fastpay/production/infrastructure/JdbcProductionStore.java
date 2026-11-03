package tech.malyah.fastpay.production.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Entradas da produção (H16), na unidade do contexto da transação (RLS). */
@Repository
public class JdbcProductionStore {

    private final JdbcTemplate jdbc;

    JdbcProductionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cria a sequência de produção da linha se ainda não existe: sem setor entra pronta. Repetir não duplica.
     *
     * @return {@code true} se criou agora
     */
    public boolean receive(UUID orderLineId, UUID sectorId, int quantity, Instant now) {
        return jdbc.update("""
                INSERT INTO production_line (id, establishment_id, order_line_id, sector_id, quantity, status,
                                             received_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                ON CONFLICT (order_line_id) DO NOTHING
                """, UUID.randomUUID(), orderLineId, sectorId, quantity,
                sectorId == null ? "PRONTO" : "AGUARDANDO_PREPARO", Timestamp.from(now)) == 1;
    }
}
