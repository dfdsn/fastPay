package tech.malyah.fastpay.jobs;

/**
 * Executor de um tipo de job. Roda numa transação com o contexto da unidade do job (RLS): o que ele
 * grava no banco é confirmado junto com a conclusão do job, ou desfeito junto com a falha.
 *
 * <p>Entrega é ao menos uma vez. Efeito fora do banco (e-mail, PSP) precisa ser idempotente pela
 * {@link JobExecution#dedupKey()} ou consultar o estado antes de repetir. Revalidar no momento da
 * execução se o efeito ainda é válido; o job não carrega autorização própria.
 */
public interface JobHandler {

    /** Identificador estável, no formato {@code modulo.acao} (minúsculas, dígitos, ponto, sublinhado). */
    String kind();

    /** Financeiros não têm teto de tentativas: seguem até situação definitiva (D-23). */
    default boolean financial() {
        return false;
    }

    void handle(JobExecution execution);
}
