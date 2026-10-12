package tech.malyah.fastpay.identity.web;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.infrastructure.TestMailbox;

/**
 * Leitura da caixa de teste, só em desenvolvimento local e só com
 * {@code fastpay.auth.test-mailbox-endpoint=true} (Compose dev). Nunca ligar em homologação ou produção:
 * expõe links de redefinição.
 */
@RestController
@ConditionalOnBooleanProperty(name = "fastpay.auth.test-mailbox-endpoint")
class TestMailboxController {

    private final TestMailbox mailbox;

    TestMailboxController(TestMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @GetMapping("/api/v1/dev/caixa-de-teste")
    List<TestMailbox.Message> messages() {
        return mailbox.messages();
    }
}
