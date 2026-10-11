package tech.malyah.fastpay.jobs.application;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.jobs.JobExecution;
import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.jobs.domain.RetryPolicy;
import tech.malyah.fastpay.jobs.domain.SafeError;
import tech.malyah.fastpay.jobs.infrastructure.JdbcJobStore;
import tech.malyah.fastpay.jobs.infrastructure.JdbcJobStore.Claimed;
import tech.malyah.fastpay.jobs.infrastructure.JobProperties;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Executa jobs vencidos. Cada busca reserva um lote com lease ({@code claim_due_jobs}); cada job roda
 * numa transação própria com o contexto da sua unidade, travando a linha até o fim. O efeito no
 * banco e a conclusão do job são confirmados juntos; se o executor falha, ambos são desfeitos e a
 * falha é registrada numa transação separada, com nova tentativa agendada ou fila de falhas.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcJobStore store;
    private final JobHandlers handlers;
    private final TenantTransactions tenants;
    private final RetryPolicy retryPolicy;
    private final JobProperties properties;

    JobWorker(JdbcJobStore store, JobHandlers handlers, TenantTransactions tenants, RetryPolicy retryPolicy,
            JobProperties properties) {
        this.store = store;
        this.handlers = handlers;
        this.tenants = tenants;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
    }

    /** Reserva e executa um lote. Devolve quantos jobs foram reservados. */
    public int runOnce() {
        List<Claimed> claimed = store.claimDue(properties.batchSize(), properties.lease());
        claimed.forEach(this::execute);
        return claimed.size();
    }

    private void execute(Claimed job) {
        try {
            tenants.runInEstablishment(job.establishmentId(),
                    () -> store.lockOwned(job.jobId(), job.leaseToken()).ifPresent(locked -> {
                        withCorrelation(locked.correlationId(), () -> {
                            var handler = handlers.find(locked.kind())
                                    .orElseThrow(() -> new JobFailure("Tipo de job sem executor registrado."));
                            handler.handle(new JobExecution(locked.jobId(), locked.establishmentId(), locked.kind(),
                                    locked.dedupKey(), locked.payload(), locked.attempts(), locked.correlationId()));
                            store.markSucceeded(locked.jobId(), job.leaseToken());
                        });
                    }));
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
        }
    }

    private void recordFailure(Claimed job, RuntimeException failure) {
        String error = SafeError.describe(failure);
        try {
            tenants.runInEstablishment(job.establishmentId(), () -> {
                Optional<JdbcJobStore.Locked> owned = store.lockOwned(job.jobId(), job.leaseToken());
                owned.ifPresent(locked -> withCorrelation(locked.correlationId(), () -> {
                    RetryPolicy.Decision decision = retryPolicy.afterFailure(locked.attempts(), locked.maxAttempts());
                    if (decision.retry()) {
                        store.scheduleRetry(locked.jobId(), job.leaseToken(), decision.delay(), error);
                        log.warn("Job {} ({}) falhou na tentativa {}; nova tentativa em {}: {}", locked.jobId(),
                                locked.kind(), locked.attempts(), decision.delay(), error);
                    } else {
                        store.markExhausted(locked.jobId(), job.leaseToken(), error);
                        log.error("Job {} ({}) esgotou {} tentativas e foi para a fila de falhas: {}", locked.jobId(),
                                locked.kind(), locked.attempts(), error);
                    }
                }));
            });
        } catch (RuntimeException unrecorded) {
            // A reserva expira e outro ciclo retoma o job; a tentativa continua contada.
            log.error("Não foi possível registrar a falha do job {}: {}", job.jobId(), SafeError.describe(unrecorded));
        }
    }

    private static void withCorrelation(String correlationId, Runnable work) {
        if (correlationId == null) {
            work.run();
            return;
        }
        try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
            work.run();
        }
    }
}
