package tech.malyah.fastpay.jobs;

import java.util.UUID;

/**
 * Dados entregues ao executor. {@code attempt} começa em 1 e conta inclusive execuções interrompidas.
 * {@code payload} é o JSON gravado ao enfileirar. Exatamente um entre {@code establishmentId} e
 * {@code identityId} é preenchido: o escopo em que o job foi gravado e em que roda.
 */
public record JobExecution(UUID jobId, UUID establishmentId, UUID identityId, String kind, String dedupKey, String payload,
        int attempt, String correlationId) {
}
