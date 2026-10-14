package tech.malyah.fastpay.mail;

import java.util.Objects;

/** Mensagem de texto simples para um destinatário. Não registrar em log: o corpo pode conter links de uso único. */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        if (to.isBlank() || to.contains("\n") || to.contains("\r")) {
            throw new IllegalArgumentException("Destinatário inválido");
        }
        if (subject.contains("\n") || subject.contains("\r")) {
            throw new IllegalArgumentException("Assunto não pode ter quebra de linha");
        }
    }
}
