package tech.malyah.fastpay.jobs.domain;

import tech.malyah.fastpay.jobs.JobFailure;

/**
 * Último erro de um job, pronto para gravar no banco e mostrar na fila de falhas.
 *
 * <p>Mensagens de exceções quaisquer podem carregar dados pessoais, tokens ou SQL; por isso só o
 * nome da classe é guardado. A mensagem só é aproveitada quando o próprio executor a declarou
 * segura, lançando {@link JobFailure}. O texto é limitado ao tamanho da coluna.
 */
public final class SafeError {

    public static final int MAX_LENGTH = 300;

    private SafeError() {
    }

    public static String describe(Throwable error) {
        if (error == null) {
            return "Falha sem detalhe.";
        }
        String text = error instanceof JobFailure failure && !failure.getMessage().isBlank()
                ? failure.getMessage().strip()
                : "Falha técnica (" + error.getClass().getSimpleName() + ").";
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }
}
