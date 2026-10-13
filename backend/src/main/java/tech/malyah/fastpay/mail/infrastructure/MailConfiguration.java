package tech.malyah.fastpay.mail.infrastructure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tech.malyah.fastpay.mail.application.EmailPort;
import tech.malyah.fastpay.mail.domain.RecipientAllowlist;

/**
 * Escolhe o transporte e recusa a subida com configuração perigosa ou incompleta, com mensagem que nomeia
 * a variável de ambiente: SMTP sem servidor, usuário, senha ou remetente; SMTP fora de produção sem lista
 * de destinatários permitidos; produção com a caixa de teste (descartaria os e-mails) ou link sem HTTPS;
 * caixa de teste exposta pela API fora de desenvolvimento.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {

    @Bean
    RecipientAllowlist recipientAllowlist(MailProperties properties, @Value("${fastpay.environment}") String environment) {
        validate(properties, environment);
        return RecipientAllowlist.of(properties.allowedRecipients());
    }

    @Bean
    EmailPort emailPort(MailProperties properties, Clock clock) {
        return switch (properties.transport()) {
            case SMTP -> new SmtpEmailPort(properties.smtp(), properties.from());
            case CAIXA_DE_TESTE -> new TestMailbox(clock);
        };
    }

    static void validate(MailProperties properties, String environment) {
        boolean production = "production".equals(environment);
        if (properties.transport() == MailProperties.Transport.SMTP) {
            List<String> missing = new ArrayList<>();
            MailProperties.Smtp smtp = properties.smtp();
            if (blank(smtp.host())) {
                missing.add("SMTP_HOST");
            }
            if (blank(smtp.username())) {
                missing.add("SMTP_USERNAME");
            }
            if (blank(smtp.password())) {
                missing.add("SMTP_PASSWORD");
            }
            if (blank(properties.from()) || properties.from().endsWith(".invalid>")) {
                missing.add("MAIL_FROM");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("E-mail por SMTP sem configuração obrigatória: defina "
                        + String.join(", ", missing) + ".");
            }
            if (!production && !RecipientAllowlist.of(properties.allowedRecipients()).restricted()) {
                throw new IllegalStateException("Fora de produção o envio real exige MAIL_ALLOWED_RECIPIENTS "
                        + "(lista de destinatários de teste permitidos).");
            }
        }
        if (production && properties.transport() == MailProperties.Transport.CAIXA_DE_TESTE) {
            throw new IllegalStateException("Produção não pode usar a caixa de teste: defina MAIL_TRANSPORT=smtp.");
        }
        if (production && !properties.publicBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("Produção exige PUBLIC_BASE_URL com https.");
        }
        if (properties.testMailboxEndpoint() && !"development".equals(environment)) {
            throw new IllegalStateException("TEST_MAILBOX_ENDPOINT só pode ser ligado em desenvolvimento.");
        }
        if (properties.testMailboxEndpoint() && properties.transport() != MailProperties.Transport.CAIXA_DE_TESTE) {
            throw new IllegalStateException("TEST_MAILBOX_ENDPOINT exige MAIL_TRANSPORT=caixa-de-teste.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
