package tech.malyah.fastpay.jobs.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.jobs.JobSummary;

/**
 * Acesso SQL à tabela {@code job} (V3). Tudo, exceto {@link #claimDue}, roda com o papel de runtime
 * dentro do contexto de uma unidade, então as policies de RLS limitam cada operação à unidade atual.
 */
@Repository
public class JdbcJobStore {

    private final JdbcTemplate jdbc;

    JdbcJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Unidade do contexto da transação atual, ou vazio se nenhuma foi definida. */
    public Optional<UUID> currentEstablishment() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT current_establishment_id()", UUID.class));
    }

    /**
     * Grava a intenção na unidade do contexto. Se o efeito já existe (mesmo tipo e chave), não grava e
     * devolve o job existente.
     */
    public Inserted insertIfAbsent(UUID id, String kind, String dedupKey, String payload, Integer maxAttempts,
            String correlationId) {
        List<UUID> created = jdbc.queryForList("""
                INSERT INTO job (id, establishment_id, kind, dedup_key, payload, max_attempts, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (establishment_id, kind, dedup_key) DO NOTHING
                RETURNING id
                """, UUID.class, id, kind, dedupKey, payload, maxAttempts, correlationId);
        if (!created.isEmpty()) {
            return new Inserted(created.getFirst(), true);
        }
        UUID existing = jdbc.queryForObject("""
                SELECT id FROM job WHERE establishment_id = current_establishment_id() AND kind = ? AND dedup_key = ?
                """, UUID.class, kind, dedupKey);
        return new Inserted(existing, false);
    }

    /** Reserva jobs vencidos de todas as unidades; devolve só metadados mínimos. */
    public List<Claimed> claimDue(int limit, Duration lease) {
        return jdbc.query("SELECT job_id, job_establishment_id, job_lease_token FROM claim_due_jobs(?, ?)",
                (rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)),
                limit, Math.toIntExact(lease.toSeconds()));
    }

    /**
     * Trava o job até o fim da transação se a reserva ainda é deste worker. Vazio quando outro worker
     * assumiu (lease expirado) ou o job já foi concluído.
     */
    public Optional<Locked> lockOwned(UUID jobId, UUID leaseToken) {
        return jdbc.query("""
                SELECT id, establishment_id, kind, dedup_key, payload::text, attempts, max_attempts, correlation_id
                FROM job WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                FOR UPDATE
                """, (rs, n) -> locked(rs), jobId, leaseToken).stream().findFirst();
    }

    public void markSucceeded(UUID jobId, UUID leaseToken) {
        requireOne(jdbc.update("""
                UPDATE job SET status = 'SUCCEEDED', lease_until = NULL, lease_token = NULL, last_error = NULL,
                       updated_at = now()
                WHERE id = ? AND lease_token = ?
                """, jobId, leaseToken));
    }

    public void scheduleRetry(UUID jobId, UUID leaseToken, Duration delay, String error) {
        requireOne(jdbc.update("""
                UPDATE job SET status = 'PENDING', lease_until = NULL, lease_token = NULL, last_error = ?,
                       next_run_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND lease_token = ?
                """, error, delay.toMillis() / 1000.0, jobId, leaseToken));
    }

    public void markExhausted(UUID jobId, UUID leaseToken, String error) {
        requireOne(jdbc.update("""
                UPDATE job SET status = 'EXHAUSTED', lease_until = NULL, lease_token = NULL, last_error = ?,
                       updated_at = now()
                WHERE id = ? AND lease_token = ?
                """, error, jobId, leaseToken));
    }

    /** Devolve um job esgotado para a fila com mais {@code extraAttempts} tentativas. */
    public boolean resumeExhausted(UUID jobId, int extraAttempts) {
        return jdbc.update("""
                UPDATE job SET status = 'PENDING', max_attempts = attempts + ?, next_run_at = now(), updated_at = now()
                WHERE id = ? AND status = 'EXHAUSTED'
                """, extraAttempts, jobId) == 1;
    }

    public List<JobSummary> exhausted(int limit) {
        return jdbc.query("""
                SELECT id, kind, dedup_key, attempts, last_error, correlation_id, updated_at
                FROM job WHERE status = 'EXHAUSTED'
                ORDER BY updated_at DESC, id
                LIMIT ?
                """, (rs, n) -> new JobSummary(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getString("dedup_key"), rs.getInt("attempts"), rs.getString("last_error"),
                rs.getString("correlation_id"), rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
                limit);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, correlationId);
    }

    private static Locked locked(ResultSet rs) throws SQLException {
        int max = rs.getInt("max_attempts");
        Integer maxAttempts = rs.wasNull() ? null : max;
        return new Locked(rs.getObject("id", UUID.class), rs.getObject("establishment_id", UUID.class),
                rs.getString("kind"), rs.getString("dedup_key"), rs.getString("payload"), rs.getInt("attempts"),
                maxAttempts, rs.getString("correlation_id"));
    }

    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Reserva do job perdida durante a execução.");
        }
    }

    public record Inserted(UUID jobId, boolean created) {
    }

    public record Claimed(UUID jobId, UUID establishmentId, UUID leaseToken) {
    }

    public record Locked(UUID jobId, UUID establishmentId, String kind, String dedupKey, String payload,
            int attempts, Integer maxAttempts, String correlationId) {
    }
}
