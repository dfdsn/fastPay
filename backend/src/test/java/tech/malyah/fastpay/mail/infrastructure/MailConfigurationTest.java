package tech.malyah.fastpay.mail.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.mail.infrastructure.MailProperties.Transport;

/** Configurações perigosas ou incompletas impedem a subida, com a variável a corrigir na mensagem. */
class MailConfigurationTest {

    static final MailProperties.Smtp GMAIL = new MailProperties.Smtp("smtp.gmail.com", 587, "fastpay.hml@gmail.com",
            "senha-de-app", true, Duration.ofSeconds(10));
    static final MailProperties.Smtp SEM_SEGREDO = new MailProperties.Smtp("smtp.gmail.com", 587, "fastpay.hml@gmail.com",
            "", true, Duration.ofSeconds(10));

    @Test
    void desenvolvimentoComCaixaDeTesteSobeSemNada() {
        assertThatCode(() -> MailConfiguration.validate(props(Transport.CAIXA_DE_TESTE, null, List.of(), true,
                "http://127.0.0.1:8088"), "development")).doesNotThrowAnyException();
    }

    @Test
    void smtpSemSenhaOuRemetenteNaoSobe() {
        assertThatThrownBy(() -> MailConfiguration.validate(props(Transport.SMTP, SEM_SEGREDO, List.of("@x.com"), false,
                "https://hml.exemplo.com"), "homologation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_PASSWORD").hasMessageContaining("MAIL_FROM")
                .hasMessageNotContaining("fastpay.hml@gmail.com");
    }

    @Test
    void smtpForaDeProducaoExigeListaDeDestinatarios() {
        var semLista = new MailProperties(Transport.SMTP, "fastPay HML <fastpay.hml@gmail.com>", "https://hml.exemplo.com",
                List.of(), ZoneId.of("America/Sao_Paulo"), false, GMAIL);

        assertThatThrownBy(() -> MailConfiguration.validate(semLista, "homologation"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_ALLOWED_RECIPIENTS");
        assertThatThrownBy(() -> MailConfiguration.validate(semLista, "development"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_ALLOWED_RECIPIENTS");
        assertThatCode(() -> MailConfiguration.validate(semLista, "production")).doesNotThrowAnyException();
    }

    @Test
    void producaoRecusaCaixaDeTesteELinkSemHttps() {
        assertThatThrownBy(() -> MailConfiguration.validate(props(Transport.CAIXA_DE_TESTE, null, List.of(), false,
                "https://fastpay.malyah.tech"), "production"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_TRANSPORT=smtp");
        assertThatThrownBy(() -> MailConfiguration.validate(new MailProperties(Transport.SMTP,
                "fastPay <nao-responda@malyah.tech>", "http://fastpay.malyah.tech", List.of(),
                ZoneId.of("America/Sao_Paulo"), false, GMAIL), "production"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PUBLIC_BASE_URL");
    }

    @Test
    void caixaLegivelPelaApiSoEmDesenvolvimento() {
        assertThatThrownBy(() -> MailConfiguration.validate(props(Transport.CAIXA_DE_TESTE, null, List.of(), true,
                "http://127.0.0.1:8089"), "homologation"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEST_MAILBOX_ENDPOINT");
    }

    @Test
    void enderecoPublicoInvalidoNaoSobe() {
        for (String invalido : List.of("127.0.0.1:8088", "http://host/", "http://host/#x", "ftp://host", "")) {
            assertThatThrownBy(() -> props(Transport.CAIXA_DE_TESTE, null, List.of(), false, invalido))
                    .as(invalido).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PUBLIC_BASE_URL");
        }
    }

    private static MailProperties props(Transport transport, MailProperties.Smtp smtp, List<String> allowed,
            boolean endpoint, String base) {
        return new MailProperties(transport, "fastPay <nao-responda@fastpay.invalid>", base, allowed,
                ZoneId.of("America/Sao_Paulo"), endpoint,
                smtp == null ? new MailProperties.Smtp(null, 587, null, null, true, Duration.ofSeconds(10)) : smtp);
    }
}
