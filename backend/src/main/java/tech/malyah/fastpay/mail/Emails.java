package tech.malyah.fastpay.mail;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
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
                    + ")" + reason(failure) + ".", failure);
        }
        count("enviado");
        log.info("E-mail {} aceito pelo transporte", effectId);
        return Delivery.SENT;
    }

    /**
     * Motivo da falha por tipo de exceção, sem o texto do servidor (que pode trazer endereços). O Spring
     * guarda as falhas de conexão e envio em {@link MailSendException#getMessageExceptions()}, fora da
     * cadeia de causas, por isso as duas são percorridas. Vazio quando o tipo não é reconhecido.
     */
    static String reason(Throwable failure) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            String label = label(current);
            if (label != null) {
                return ": " + label + " (" + current.getClass().getSimpleName() + ")";
            }
            if (current instanceof MailSendException send) {
                Collections.addAll(pending, send.getMessageExceptions());
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return "";
    }

    private static String label(Throwable error) {
        if (error instanceof AuthenticationFailedException || error instanceof MailAuthenticationException) {
            return "usuário ou senha recusados";
        }
        if (error instanceof UnknownHostException) {
            return "servidor não encontrado (DNS)";
        }
        if (error instanceof ConnectException || error instanceof NoRouteToHostException
                || "MailConnectException".equals(error.getClass().getSimpleName())) {
            return "sem conexão com o servidor";
        }
        if (error instanceof SocketTimeoutException) {
            return "tempo esgotado na conexão";
        }
        if (error instanceof SSLException) {
            return "falha de TLS (certificado não confiável ou conexão interceptada)";
        }
        if (error instanceof EOFException) {
            return "conexão encerrada no meio do caminho";
        }
        if (error instanceof SendFailedException) {
            return "remetente ou destinatário recusado";
        }
        return null;
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
