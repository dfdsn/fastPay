package tech.malyah.fastpay.identity.infrastructure;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.application.PasswordResetMailer;

/**
 * Remetente de teste (H08): guarda as últimas mensagens em memória, sem enviar a ninguém. Vale até o
 * e-mail durável de H12. Em desenvolvimento, a caixa pode ser lida pela API quando
 * {@code fastpay.auth.test-mailbox-endpoint=true}; o log não leva destinatário nem link.
 */
@Component
public class TestMailbox implements PasswordResetMailer {

    static final int CAPACITY = 50;

    private static final Logger log = LoggerFactory.getLogger(TestMailbox.class);

    public record Message(String to, String subject, String body, Instant expiresAt) {
    }

    private final Deque<Message> messages = new ArrayDeque<>();

    @Override
    public void sendPasswordReset(String email, String displayName, String link, Instant expiresAt) {
        String body = """
                Olá, %s.

                Para criar uma nova senha no fastPay, abra o link abaixo. Ele vale por 30 minutos e só pode \
                ser usado uma vez:

                %s

                Se você não pediu a troca de senha, ignore esta mensagem. Sua senha atual continua valendo.
                """.formatted(displayName, link);
        store(new Message(email, "Redefinição de senha do fastPay", body, expiresAt));
        log.info("Mensagem de redefinição de senha guardada na caixa de teste (sem envio real até H12)");
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

    private synchronized void store(Message message) {
        messages.addFirst(message);
        while (messages.size() > CAPACITY) {
            messages.removeLast();
        }
    }
}
