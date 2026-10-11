package tech.malyah.fastpay.jobs.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração técnica da fila de jobs ({@code fastpay.jobs.*}). Valores iniciais da especificação §9:
 * 5 tentativas totais para não financeiros, backoff progressivo. Não é limite comercial de loja.
 *
 * @param workerEnabled liga o worker periódico; desligado, os jobs ficam pendentes até outro worker
 * @param pollInterval  intervalo entre buscas de jobs vencidos
 * @param batchSize     jobs reservados por busca (1..100)
 * @param lease         prazo da reserva; depois dele um job de worker que caiu volta a ser executável
 */
@ConfigurationProperties("fastpay.jobs")
public record JobProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5m") Duration lease,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("15s") Duration initialBackoff,
        @DefaultValue("2.0") double backoffMultiplier,
        @DefaultValue("5m") Duration maxBackoff) {

    public JobProperties {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("fastpay.jobs.batch-size deve estar entre 1 e 100");
        }
        if (lease.toSeconds() < 1 || lease.toSeconds() > 3600) {
            throw new IllegalArgumentException("fastpay.jobs.lease deve estar entre 1s e 1h");
        }
    }
}
