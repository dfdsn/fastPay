package tech.malyah.fastpay.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tentativas de autenticação para limitar abuso (T-03). Tabela global e técnica: as chaves já chegam
 * como hash. Cada comando é uma instrução isolada, fora de contexto de unidade.
 */
@Repository
public class JdbcAuthAttemptStore {

    /** Tentativas mais antigas que isto não entram em nenhuma janela e são apagadas. */
    static final Duration RETENTION = Duration.ofDays(1);

    public enum Kind {
        LOGIN_FALHOU,
        REDEFINICAO_PEDIDA,
        REDEFINICAO_FALHOU,
        MFA_FALHOU,
        CONVITE_FALHOU
    }

    private final JdbcTemplate jdbc;

    JdbcAuthAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Kind kind, String subjectKey, String clientKey, Instant at) {
        jdbc.update("DELETE FROM auth_attempt WHERE occurred_at < ?", Timestamp.from(at.minus(RETENTION)));
        jdbc.update("INSERT INTO auth_attempt (id, kind, subject_key, client_key, occurred_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), kind.name(), subjectKey, clientKey, Timestamp.from(at));
    }

    public long countBySubject(Kind kind, String subjectKey, Instant since) {
        return count("subject_key", kind, subjectKey, since);
    }

    public long countByClient(Kind kind, String clientKey, Instant since) {
        return count("client_key", kind, clientKey, since);
    }

    /** Login certo zera as falhas da conta; as da origem continuam contando. */
    public void clearSubject(Kind kind, String subjectKey) {
        jdbc.update("DELETE FROM auth_attempt WHERE kind = ? AND subject_key = ?", kind.name(), subjectKey);
    }

    private long count(String column, Kind kind, String key, Instant since) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM auth_attempt WHERE kind = ? AND " + column
                + " = ? AND occurred_at > ?", Long.class, kind.name(), key, Timestamp.from(since));
        return total == null ? 0 : total;
    }
}
