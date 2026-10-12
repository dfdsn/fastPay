package tech.malyah.fastpay.identity.application;

import java.time.Instant;

/**
 * Envio do link de redefinição de senha. Até H12 a única implementação é a caixa de teste em memória
 * (nenhum destinatário real); H12 troca pelo e-mail durável da fila de jobs.
 */
public interface PasswordResetMailer {

    void sendPasswordReset(String email, String displayName, String link, Instant expiresAt);
}
