package tech.malyah.fastpay.mail;

import java.util.Objects;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.mail.application.EmailPort;
import tech.malyah.fastpay.mail.domain.RecipientAllowlist;
import tech.malyah.fastpay.mail.infrastructure.MailProperties;

/**
 * Envio de e-mail a partir de um executor da fila de jobs (H12). Fora de produção, marca o ambiente no
 * assunto e no corpo; com lista de destinatários configurada, não envia a quem estiver fora dela.
 *
 * <p>Falha do servidor vira {@link JobFailure} com mensagem segura: a fila agenda nova tentativa e, depois
 * de 5 no total, põe o job na fila de falhas. A entrega é ao menos uma vez; o {@code Message-ID} é o mesmo
 * em todas as tentativas do mesmo job.
 */
@Service
public class Emails {

    private static final Logger log = LoggerFactory.getLogger(Emails.class);

    public enum Delivery {
        /** Servidor aceitou a mensagem (ou a caixa de teste a guardou). */
        SENT,
        /** Destinatário fora da lista permitida: nada foi enviado. Não é falha a repetir. */
        BLOCKED
    }

    private final EmailPort port;
    private final RecipientAllowlist allowlist;
    private final MeterRegistry meters;
    private final String environment;
    private final String messageIdDomain;

    Emails(EmailPort port, RecipientAllowlist allowlist, MeterRegistry meters, MailProperties properties,
            @Value("${fastpay.environment}") String environment) {
        this.port = port;
        this.allowlist = allowlist;
        this.meters = meters;
        this.environment = environment;
        String from = properties.from();
        int at = from.lastIndexOf('@');
        this.messageIdDomain = at < 0 ? "fastpay" : from.substring(at + 1).replace(">", "").strip();
    }

    /**
     * @param effectId identificador estável do efeito (o id do job): mesmo valor em todas as tentativas
     */
    public Delivery send(EmailMessage message, UUID effectId) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(effectId, "effectId");
        if (!allowlist.allows(message.to())) {
            count("bloqueado");
            log.warn("E-mail {} não enviado: destinatário fora da lista de destinatários permitidos ({})", effectId,
                    environment);
            return Delivery.BLOCKED;
        }
        try {
            port.send(tagged(message), "<" + effectId + "@" + messageIdDomain + ">");
        } catch (RuntimeException failure) {
            count("falhou");
            throw new JobFailure("Servidor de e-mail não aceitou a mensagem (" + failure.getClass().getSimpleName()
                    + ").", failure);
        }
        count("enviado");
        log.info("E-mail {} aceito pelo transporte", effectId);
        return Delivery.SENT;
    }

    private EmailMessage tagged(EmailMessage message) {
        String label = switch (environment) {
            case "production" -> null;
            case "homologation" -> "Homologação";
            default -> "Desenvolvimento";
        };
        if (label == null) {
            return message;
        }
        return new EmailMessage(message.to(), "[" + label + "] " + message.subject(),
                message.body() + "\n--\nMensagem do ambiente de " + label.toLowerCase(java.util.Locale.ROOT)
                        + " do fastPay. Não é um ambiente real.\n");
    }

    private void count(String result) {
        meters.counter("fastpay.email", "resultado", result).increment();
    }
}
