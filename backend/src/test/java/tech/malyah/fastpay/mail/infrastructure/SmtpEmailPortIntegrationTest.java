package tech.malyah.fastpay.mail.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tech.malyah.fastpay.mail.EmailMessage;

/**
 * Envio por SMTP real contra um servidor de teste (Mailpit), sem destinatário real: a mensagem chega com
 * assunto em UTF-8 e o {@code Message-ID} do efeito; sem STARTTLS disponível o envio é recusado; servidor fora
 * do ar vira exceção.
 */
@Testcontainers
class SmtpEmailPortIntegrationTest {

    static final String IMAGE =
            "axllent/mailpit:v1.27.10@sha256:b1f1be18af530d939a11ee8820b379e0c88eeec204d904bfad68862adced3a5a";

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "1")
            .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "1")
            .waitingFor(Wait.forHttp("/livez").forPort(8025));

    @Test
    void servidorRecebeAMensagemComAssuntoAcentuadoEMessageIdDoEfeito() throws Exception {
        String id = "<" + UUID.randomUUID() + "@fastpay.hml.test>";
        SmtpEmailPort porta = new SmtpEmailPort(smtp(MAILPIT.getMappedPort(1025), false), "fastPay HML <fastpay@hml.test>");

        porta.send(new EmailMessage("dono@permitido.test", "Redefinição de senha do fastPay", "Olá, Ana.\nLink: x"), id);

        String caixa = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(caixa).contains("Redefinição de senha do fastPay", "dono@permitido.test", "fastpay@hml.test",
                id.substring(1, id.length() - 1));
    }

    @Test
    void semStarttlsNoServidorNaoEnvia() {
        SmtpEmailPort porta = new SmtpEmailPort(smtp(MAILPIT.getMappedPort(1025), true), "fastpay@hml.test");

        assertThatThrownBy(() -> porta.send(new EmailMessage("dono@permitido.test", "Teste", "x"), "<a@b>"))
                .isInstanceOf(MailException.class);
    }

    @Test
    void servidorForaDoArViraExcecao() {
        SmtpEmailPort porta = new SmtpEmailPort(new MailProperties.Smtp("127.0.0.1", 1, "u", "p", false,
                Duration.ofSeconds(2)), "fastpay@hml.test");

        assertThatThrownBy(() -> porta.send(new EmailMessage("dono@permitido.test", "Teste", "x"), "<a@b>"))
                .isInstanceOf(MailException.class);
    }

    private static MailProperties.Smtp smtp(int port, boolean startTls) {
        return new MailProperties.Smtp(MAILPIT.getHost(), port, "fastpay-teste", "senha-de-teste", startTls,
                Duration.ofSeconds(5));
    }
}
