package tech.malyah.fastpay.mail.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.malyah.fastpay.mail.EmailMessage;
import tech.malyah.fastpay.mail.application.EmailPort;

/**
 * Caixa de teste (transporte {@code caixa-de-teste}): guarda as últimas mensagens em memória, sem enviar a
 * ninguém. Em desenvolvimento pode ser lida pela API ({@code fastpay.mail.test-mailbox-endpoint=true}); o
 * log não leva destinatário nem link.
 */
public class TestMailbox implements EmailPort {

    static final int CAPACITY = 50;

    private static final Logger log = LoggerFactory.getLogger(TestMailbox.class);

    public record Message(String to, String subject, String body, String messageId, Instant at) {
    }

    private final Deque<Message> messages = new ArrayDeque<>();
    private final Clock clock;

    public TestMailbox(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void send(EmailMessage message, String messageId) {
        messages.addFirst(new Message(message.to(), message.subject(), message.body(), messageId, clock.instant()));
        while (messages.size() > CAPACITY) {
            messages.removeLast();
        }
        log.info("Mensagem {} guardada na caixa de teste (nenhum envio real)", messageId);
    }

    /** Mais recentes primeiro. */
    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    public synchronized Optional<Message> latestTo(String email) {
        return messages.stream().filter(m -> m.to().equalsIgnoreCase(email)).findFirst();
    }

    public synchronized void clear() {
        messages.clear();
    }
}
