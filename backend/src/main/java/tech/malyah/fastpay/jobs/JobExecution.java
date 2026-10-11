package tech.malyah.fastpay.jobs;

import java.util.UUID;

/**
 * Dados entregues ao executor. {@code attempt} começa em 1 e conta inclusive execuções interrompidas.
 * {@code payload} é o JSON gravado ao enfileirar.
 */
public record JobExecution(UUID jobId, UUID establishmentId, String kind, String dedupKey, String payload,
        int attempt, String correlationId) {
}
