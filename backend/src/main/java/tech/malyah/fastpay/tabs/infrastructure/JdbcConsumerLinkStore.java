package tech.malyah.fastpay.tabs.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Vínculos de comandas a contas de consumidor com Google (H27, tabela {@code consumer_tab_link}). Na unidade, a RLS
 * mostra só os vínculos das comandas dela (sem a conta); com a conta no contexto, só os vínculos e as comandas dela.
 */
@Repository
public class JdbcConsumerLinkStore {

    /** Comanda do histórico, lida no contexto da conta. */
    public record HistoryRow(UUID tabId, String establishmentName, int number, String displayName, Instant openedAt,
            Instant closedAt, Instant linkedAt) {
    }

    private final JdbcTemplate jdbc;

    JdbcConsumerLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Conta vinculada à comanda, se houver (contexto da unidade). */
    public Optional<UUID> accountOf(UUID tabId) {
        return jdbc.query("SELECT consumer_account_id FROM consumer_tab_link WHERE tab_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tabId).stream().findFirst();
    }

    /** Grava o vínculo com a conta e a unidade do contexto. Uma conta por comanda (chave única). */
    public void insert(UUID id, UUID tabId, UUID grantId, Instant at) {
        jdbc.update("""
                INSERT INTO consumer_tab_link (id, consumer_account_id, establishment_id, tab_id, grant_id, linked_at)
                VALUES (?, current_consumer_account_id(), current_establishment_id(), ?, ?, ?)
                """, id, tabId, grantId, Timestamp.from(at));
    }

    /** Histórico da conta do contexto, das comandas mais recentes para as mais antigas. */
    public List<HistoryRow> history(int limit, long offset) {
        return jdbc.query("""
                SELECT l.tab_id, e.name, t.number, t.display_name, t.opened_at, t.closed_at, l.linked_at
                FROM consumer_tab_link l
                JOIN tab t ON t.id = l.tab_id
                JOIN establishment e ON e.id = l.establishment_id
                WHERE l.consumer_account_id = current_consumer_account_id()
                ORDER BY t.opened_at DESC, l.id
                LIMIT ? OFFSET ?
                """, (rs, i) -> new HistoryRow(rs.getObject("tab_id", UUID.class), rs.getString("name"),
                rs.getInt("number"), rs.getString("display_name"), rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                rs.getTimestamp("linked_at").toInstant()), limit, offset);
    }

    public long historySize() {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM consumer_tab_link WHERE consumer_account_id = current_consumer_account_id()",
                Long.class);
        return total == null ? 0 : total;
    }
}
