package tech.malyah.fastpay.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.mail.application.EmailPort;
import tech.malyah.fastpay.mail.domain.RecipientAllowlist;
import tech.malyah.fastpay.mail.infrastructure.MailProperties;

class EmailsTest {

    record Sent(EmailMessage message, String messageId) {
    }

    final List<Sent> enviados = new ArrayList<>();
    final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    RuntimeException falha;

    final EmailPort porta = (message, messageId) -> {
        if (falha != null) {
            throw falha;
        }
        enviados.add(new Sent(message, messageId));
    };

    final EmailMessage convite = new EmailMessage("dono@permitido.test", "Convite", "Corpo do convite");

    @Test
    void homologacaoMarcaOAmbienteNoAssuntoENoCorpo() {
        UUID efeito = UUID.randomUUID();

        assertThat(emails("homologation", List.of("@permitido.test")).send(convite, efeito))
                .isEqualTo(Emails.Delivery.SENT);

        assertThat(enviados).singleElement().satisfies(s -> {
            assertThat(s.message().subject()).isEqualTo("[Homologação] Convite");
            assertThat(s.message().body()).startsWith("Corpo do convite").contains("ambiente de homologação");
            assertThat(s.messageId()).isEqualTo("<" + efeito + "@fastpay.hml.test>");
        });
        assertThat(metricas.counter("fastpay.email", "resultado", "enviado").count()).isEqualTo(1);
    }

    @Test
    void producaoNaoMarcaAmbiente() {
        emails("production", List.of()).send(convite, UUID.randomUUID());

        assertThat(enviados).singleElement().satisfies(s -> assertThat(s.message()).isEqualTo(convite));
    }

    @Test
    void destinatarioForaDaListaNaoRecebe() {
        Emails emails = emails("homologation", List.of("@permitido.test"));

        assertThat(emails.send(new EmailMessage("cliente.real@gmail.com", "Convite", "x"), UUID.randomUUID()))
                .isEqualTo(Emails.Delivery.BLOCKED);

        assertThat(enviados).isEmpty();
        assertThat(metricas.counter("fastpay.email", "resultado", "bloqueado").count()).isEqualTo(1);
    }

    @Test
    void falhaDoServidorViraFalhaDeJobSemDetalhesDoServidor() {
        falha = new MailSendException("535 credenciais recusadas para fastpay.hml@gmail.com em smtp.gmail.com");
        UUID efeito = UUID.randomUUID();

        assertThatThrownBy(() -> emails("homologation", List.of("@permitido.test")).send(convite, efeito))
                .isInstanceOf(JobFailure.class)
                .hasMessage("Servidor de e-mail não aceitou a mensagem (MailSendException).");
        assertThat(metricas.counter("fastpay.email", "resultado", "falhou").count()).isEqualTo(1);
    }

    private Emails emails(String ambiente, List<String> permitidos) {
        MailProperties props = new MailProperties(MailProperties.Transport.SMTP, "fastPay <fastpay@fastpay.hml.test>",
                "https://hml.exemplo.com", permitidos, ZoneId.of("America/Sao_Paulo"), false,
                new MailProperties.Smtp("smtp.exemplo.com", 587, "u", "p", true, Duration.ofSeconds(10)));
        return new Emails(porta, RecipientAllowlist.of(permitidos), metricas, props, ambiente);
    }
}
