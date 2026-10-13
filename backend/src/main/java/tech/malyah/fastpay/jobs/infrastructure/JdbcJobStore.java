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
 * Acesso SQL à tabela {@code job} (V3, V7). Tudo, exceto {@link #claimDue}, roda com o papel de runtime
 * dentro do contexto de uma unidade ou de uma identidade (jobs da conta, H12), então as policies de RLS
 * limitam cada operação ao escopo atual.
 */
@Repository
public class JdbcJobStore {

    private final JdbcTemplate jdbc;

    JdbcJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Escopo do contexto da transação atual: a unidade, se houver; senão a identidade (jobs da conta); vazio
     * se nenhum foi definido.
     */
    public Optional<Scope> currentScope() {
        return jdbc.query("SELECT current_establishment_id(), current_identity_id()", (rs, n) -> {
            UUID establishment = rs.getObject(1, UUID.class);
            UUID identity = rs.getObject(2, UUID.class);
            if (establishment != null) {
                return new Scope(establishment, null);
            }
            return identity == null ? null : new Scope(null, identity);
        }).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * Grava a intenção no escopo do contexto. Se o efeito já existe (mesmo tipo e chave), não grava e
     * devolve o job existente.
     */
    public Inserted insertIfAbsent(Scope scope, UUID id, String kind, String dedupKey, String payload,
            Integer maxAttempts, String correlationId) {
        String owner = scope.establishmentId() != null ? "establishment_id" : "identity_id";
        String current = scope.establishmentId() != null ? "current_establishment_id()" : "current_identity_id()";
        String conflict = scope.establishmentId() != null
                ? "(establishment_id, kind, dedup_key)"
                : "(identity_id, kind, dedup_key) WHERE identity_id IS NOT NULL";
        List<UUID> created = jdbc.queryForList("""
                INSERT INTO job (id, %s, kind, dedup_key, payload, max_attempts, correlation_id)
                VALUES (?, %s, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT %s DO NOTHING
                RETURNING id
                """.formatted(owner, current, conflict), UUID.class, id, kind, dedupKey, payload, maxAttempts,
                correlationId);
        if (!created.isEmpty()) {
            return new Inserted(created.getFirst(), true);
        }
        UUID existing = jdbc.queryForObject("SELECT id FROM job WHERE %s = %s AND kind = ? AND dedup_key = ?"
                .formatted(owner, current), UUID.class, kind, dedupKey);
        return new Inserted(existing, false);
    }

    /** Reserva jobs vencidos de todas as unidades e identidades; devolve só metadados mínimos. */
    public List<Claimed> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                SELECT job_id, job_establishment_id, job_identity_id, job_lease_token FROM claim_due_jobs(?, ?)
                """, (rs, n) -> new Claimed(rs.getObject(1, UUID.class),
                new Scope(rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), rs.getObject(4, UUID.class)),
                limit, Math.toIntExact(lease.toSeconds()));
    }

    /**
     * Trava o job até o fim da transação se a reserva ainda é deste worker. Vazio quando outro worker
     * assumiu (lease expirado) ou o job já foi concluído.
     */
    public Optional<Locked> lockOwned(UUID jobId, UUID leaseToken) {
        return jdbc.query("""
                SELECT id, establishment_id, identity_id, kind, dedup_key, payload::text, attempts, max_attempts, correlation_id
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
        return new Locked(rs.getObject("id", UUID.class),
                new Scope(rs.getObject("establishment_id", UUID.class), rs.getObject("identity_id", UUID.class)),
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

    /** Dono do job: exatamente um entre unidade e identidade (V7). */
    public record Scope(UUID establishmentId, UUID identityId) {
    }

    public record Claimed(UUID jobId, Scope scope, UUID leaseToken) {
    }

    public record Locked(UUID jobId, Scope scope, String kind, String dedupKey, String payload,
            int attempts, Integer maxAttempts, String correlationId) {
    }
}
