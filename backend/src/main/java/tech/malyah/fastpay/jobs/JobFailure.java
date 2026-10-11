package tech.malyah.fastpay.jobs;

import java.util.Objects;

/**
 * Falha esperada de um executor de job, com mensagem segura em português: é gravada como último erro
 * e aparece na fila de falhas. Não incluir dados pessoais, tokens ou valores de cartão. Qualquer
 * outra exceção é registrada só pelo nome da classe.
 */
public class JobFailure extends RuntimeException {

    public JobFailure(String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
    }

    public JobFailure(String safeMessage, Throwable cause) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"), cause);
    }
}
