package tech.malyah.fastpay.mail.web;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.mail.application.EmailPort;
import tech.malyah.fastpay.mail.infrastructure.TestMailbox;

/**
 * Leitura da caixa de teste, só em desenvolvimento local e só com
 * {@code fastpay.mail.test-mailbox-endpoint=true} (Compose dev). A subida recusa a opção fora de
 * desenvolvimento: expõe links de redefinição e convites.
 */
@RestController
@ConditionalOnBooleanProperty(name = "fastpay.mail.test-mailbox-endpoint")
class TestMailboxController {

    private final TestMailbox mailbox;

    TestMailboxController(EmailPort port) {
        this.mailbox = (TestMailbox) port;
    }

    @GetMapping("/api/v1/dev/caixa-de-teste")
    List<TestMailbox.Message> messages() {
        return mailbox.messages();
    }
}
