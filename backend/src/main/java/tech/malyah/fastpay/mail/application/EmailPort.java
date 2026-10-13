package tech.malyah.fastpay.mail.application;

import tech.malyah.fastpay.mail.EmailMessage;

/**
 * Transporte de e-mail: SMTP (Gmail em homologação, SES em produção, H49) ou caixa de teste em memória.
 * Retorna quando o servidor aceitou a mensagem; qualquer exceção é tratada como falha do envio.
 *
 * @param messageId cabeçalho {@code Message-ID} estável por efeito, para quem recebe reconhecer repetição
 */
public interface EmailPort {

    void send(EmailMessage message, String messageId);
}
