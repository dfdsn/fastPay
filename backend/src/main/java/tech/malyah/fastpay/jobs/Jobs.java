package tech.malyah.fastpay.jobs;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tech.malyah.fastpay.jobs.application.JobHandlers;
import tech.malyah.fastpay.jobs.domain.RetryPolicy;
import tech.malyah.fastpay.jobs.infrastructure.JdbcJobStore;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Entrada dos outros módulos na fila de efeitos assíncronos (H04, especificação §9).
 */
@Service
public class Jobs {

    private final JdbcJobStore store;
    private final JobHandlers handlers;
    private final RetryPolicy retryPolicy;
    private final TenantTransactions tenants;

    Jobs(JdbcJobStore store, JobHandlers handlers, RetryPolicy retryPolicy, TenantTransactions tenants) {
        this.store = store;
        this.handlers = handlers;
        this.retryPolicy = retryPolicy;
        this.tenants = tenants;
    }

    /**
     * Grava a intenção do efeito na transação corrente, que deve ser a do fato de negócio e ter contexto
     * de unidade ({@link TenantTransactions}). Commit publica, rollback descarta. Pedir de novo o mesmo
     * efeito devolve o job existente sem criar outro.
     */
    public Enqueued enqueue(JobRequest request) {
        Objects.requireNonNull(request, "request");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A intenção do job deve ser gravada na transação do fato de negócio.");
        }
        if (store.currentEstablishment().isEmpty()) {
            throw new IllegalStateException("Job exige contexto de estabelecimento na transação.");
        }
        JobHandler handler = handlers.find(request.kind())
                .orElseThrow(() -> new IllegalArgumentException("Tipo de job sem executor: " + request.kind()));
        JdbcJobStore.Inserted inserted = store.insertIfAbsent(UUID.randomUUID(), request.kind(), request.dedupKey(),
                request.payload(), retryPolicy.maxAttemptsFor(handler.financial()), request.correlationId());
        return new Enqueued(inserted.jobId(), inserted.created());
    }

    /** Fila de falhas da unidade: jobs que esgotaram as tentativas, mais recentes primeiro (até 100). */
    public List<JobSummary> exhausted(UUID establishmentId) {
        return tenants.inEstablishment(establishmentId, () -> store.exhausted(100));
    }

    /**
     * Retomada da mesma operação a partir da fila de falhas, com novo ciclo de tentativas e registro em
     * auditoria. Quem chama já autorizou o ator para a unidade e para uma permissão equivalente à da
     * ação original (a checagem de permissão chega com H07/H11); o executor revalida o efeito ao rodar.
     *
     * @return {@code false} se o job não existe na unidade ou não está esgotado
     */
    public boolean retryExhausted(UUID establishmentId, UUID jobId, UUID actorId, String correlationId) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(actorId, "actorId");
        return tenants.inEstablishment(establishmentId, () -> {
            if (!store.resumeExhausted(jobId, retryPolicy.maxAttempts())) {
                return false;
            }
            store.audit("job.retomada_autorizada", jobId, actorId, correlationId);
            return true;
        });
    }

    /** Resultado do enfileiramento; {@code created} falso quando o efeito já tinha job. */
    public record Enqueued(UUID jobId, boolean created) {
    }
}
