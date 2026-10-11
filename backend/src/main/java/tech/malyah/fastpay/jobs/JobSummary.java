package tech.malyah.fastpay.jobs;

import java.time.Instant;
import java.util.UUID;

/** Item da fila de falhas: job esgotado aguardando retomada autorizada. */
public record JobSummary(UUID jobId, String kind, String dedupKey, int attempts, String lastError,
        String correlationId, Instant updatedAt) {
}
