package tech.malyah.fastpay.jobs;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * de unidade ou, para efeitos da conta de uma pessoa sem unidade (H12), de identidade
     * ({@link TenantTransactions}). O job roda no mesmo escopo. Commit publica, rollback descarta. Pedir de novo o mesmo
     * efeito devolve o job existente sem criar outro.
     */
    public Enqueued enqueue(JobRequest request) {
        Objects.requireNonNull(request, "request");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A intenção do job deve ser gravada na transação do fato de negócio.");
        }
        JdbcJobStore.Scope scope = store.currentScope().orElseThrow(
                () -> new IllegalStateException("Job exige contexto de estabelecimento ou de identidade na transação."));
        JobHandler handler = handlers.find(request.kind())
                .orElseThrow(() -> new IllegalArgumentException("Tipo de job sem executor: " + request.kind()));
        JdbcJobStore.Inserted inserted = store.insertIfAbsent(scope, UUID.randomUUID(), request.kind(), request.dedupKey(),
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

    /** Situação de um efeito para mostrar a quem o pediu (H16): em andamento, feito ou na fila de falhas. */
    public enum EffectState {
        /** Aguardando, rodando ou com nova tentativa marcada. */
        PENDENTE,
        CONCLUIDO,
        /** Esgotou as tentativas: está na fila de falhas e pode ser retomado. */
        FALHOU
    }

    /**
     * Situação dos efeitos de um tipo pelas chaves de deduplicação, na transação de quem chama (contexto da unidade).
     * Chave sem job não aparece no mapa.
     */
    public Map<String, EffectState> statesInCurrentTransaction(String kind, Collection<String> dedupKeys) {
        Objects.requireNonNull(kind, "kind");
        Map<String, EffectState> states = new HashMap<>();
        store.statusByDedupKey(kind, dedupKeys).forEach((key, row) -> states.put(key, switch (row.status()) {
            case "SUCCEEDED" -> EffectState.CONCLUIDO;
            case "EXHAUSTED" -> EffectState.FALHOU;
            default -> EffectState.PENDENTE;
        }));
        return states;
    }

    /**
     * Como {@link #retryExhausted}, achando o job pelo tipo e pela chave, na transação de quem chama, que já autorizou o
     * ator na unidade com a permissão da ação original (H16: reprocessar o encaminhamento sem outro lançamento).
     *
     * @return {@code false} se o efeito não tem job ou não está na fila de falhas
     */
    public boolean retryExhaustedInCurrentTransaction(String kind, String dedupKey, UUID actorId,
            String correlationId) {
        Objects.requireNonNull(actorId, "actorId");
        JdbcJobStore.StatusRow row = store.statusByDedupKey(kind, List.of(dedupKey)).get(dedupKey);
        if (row == null || !store.resumeExhausted(row.jobId(), retryPolicy.maxAttempts())) {
            return false;
        }
        store.audit("job.retomada_autorizada", row.jobId(), actorId, correlationId);
        return true;
    }

    /** Resultado do enfileiramento; {@code created} falso quando o efeito já tinha job. */
    public record Enqueued(UUID jobId, boolean created) {
    }
}
