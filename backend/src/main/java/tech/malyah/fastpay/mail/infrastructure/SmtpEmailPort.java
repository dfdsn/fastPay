package tech.malyah.fastpay.mail.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import tech.malyah.fastpay.mail.EmailMessage;
import tech.malyah.fastpay.mail.application.EmailPort;

/**
 * Envio por SMTP com STARTTLS obrigatório quando ligado e prazos curtos: o envio roda dentro da transação
 * do job e não pode prendê-la. Retorna quando o servidor aceitou a mensagem.
 */
class SmtpEmailPort implements EmailPort {

    private final JavaMailSenderImpl sender;
    private final InternetAddress from;

    SmtpEmailPort(MailProperties.Smtp smtp, String from) {
        this.sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        String timeout = String.valueOf(smtp.timeout().toMillis());
        props.put("mail.smtp.auth", String.valueOf(smtp.username() != null && !smtp.username().isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(smtp.startTls()));
        props.put("mail.smtp.starttls.required", String.valueOf(smtp.startTls()));
        props.put("mail.smtp.connectiontimeout", timeout);
        props.put("mail.smtp.timeout", timeout);
        props.put("mail.smtp.writetimeout", timeout);
        try {
            this.from = new InternetAddress(from, true);
        } catch (jakarta.mail.internet.AddressException invalid) {
            throw new IllegalArgumentException("fastpay.mail.from (MAIL_FROM) não é um endereço válido");
        }
    }

    @Override
    public void send(EmailMessage message, String messageId) {
        MimeMessage mime = new MimeMessage(sender.getSession()) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        try {
            mime.setFrom(from);
            mime.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(message.to(), true));
            mime.setSubject(message.subject(), StandardCharsets.UTF_8.name());
            mime.setText(message.body(), StandardCharsets.UTF_8.name());
        } catch (MessagingException invalid) {
            throw new MailSendException("Mensagem inválida", invalid);
        }
        sender.send(mime);
    }
}
