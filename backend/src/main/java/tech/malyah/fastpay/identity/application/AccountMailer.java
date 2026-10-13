package tech.malyah.fastpay.identity.application;

import java.time.Instant;

/**
 * Mensagens da conta da equipe: link de redefinição de senha (H08) e aviso de troca do autenticador
 * (H09). Até H12 a única implementação é a caixa de teste em memória (nenhum destinatário real); H12
 * troca pelo e-mail durável da fila de jobs.
 */
public interface AccountMailer {

    void sendPasswordReset(String email, String displayName, String link, Instant expiresAt);

    /** Aviso de que o autenticador e os códigos de recuperação foram trocados e as sessões encerradas. */
    void sendMfaReplaced(String email, String displayName, Instant at);
}
