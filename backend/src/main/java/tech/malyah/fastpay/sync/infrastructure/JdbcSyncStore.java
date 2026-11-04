package tech.malyah.fastpay.sync.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.sync.Change;
import tech.malyah.fastpay.sync.Topic;

/** Avisos de mudança da unidade do contexto da transação (RLS, V14). */
@Repository
public class JdbcSyncStore {

    private final JdbcTemplate jdbc;

    JdbcSyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Próxima posição da sequência da unidade. Trava a linha da unidade até o fim da transação: as posições ficam na
     * ordem dos commits e sem buracos (rollback devolve a posição).
     */
    public long next() {
        Long seq = jdbc.queryForObject("""
                INSERT INTO sync_cursor (establishment_id, last_seq) VALUES (current_establishment_id(), 1)
                ON CONFLICT (establishment_id) DO UPDATE SET last_seq = sync_cursor.last_seq + 1
                RETURNING last_seq
                """, Long.class);
        return seq == null ? 0 : seq;
    }

    public void insert(long seq, Topic topic, UUID tabId, Instant now) {
        jdbc.update("""
                INSERT INTO sync_change (establishment_id, seq, topic, tab_id, changed_at)
                VALUES (current_establishment_id(), ?, ?, ?, ?)
                """, seq, topic.name(), tabId, Timestamp.from(now));
    }

    /** Última posição gravada; 0 se a unidade ainda não tem avisos. */
    public long last() {
        List<Long> found = jdbc.queryForList("SELECT last_seq FROM sync_cursor", Long.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    /** Avisos com posição em {@code (after, upTo]}, em ordem. */
    public List<Change> between(long after, long upTo) {
        return jdbc.query("""
                SELECT seq, topic, tab_id FROM sync_change WHERE seq > ? AND seq <= ? ORDER BY seq
                """, (rs, n) -> new Change(rs.getLong("seq"), Topic.valueOf(rs.getString("topic")),
                rs.getObject("tab_id", UUID.class)), after, upTo);
    }

    /** Retenção: apaga avisos anteriores ao instante. */
    public int deleteBefore(Instant limit) {
        return jdbc.update("DELETE FROM sync_change WHERE changed_at < ?", Timestamp.from(limit));
    }
}
