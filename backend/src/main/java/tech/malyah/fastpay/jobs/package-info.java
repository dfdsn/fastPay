/**
 * Efeitos assíncronos duráveis (H04): intenção gravada na transação do fato, deduplicação por efeito,
 * reserva com lease pelo worker, execução no contexto da unidade, tentativas limitadas para não
 * financeiros, fila de falhas e retomada autorizada. API pública: {@link tech.malyah.fastpay.jobs.Jobs},
 * {@link tech.malyah.fastpay.jobs.JobHandler} e tipos associados.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Jobs e efeitos assíncronos")
package tech.malyah.fastpay.jobs;
