package tech.malyah.fastpay.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.mail.infrastructure.MailProperties;

class EmailTemplatesTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv_-0123456789";

    final MailProperties props = new MailProperties(MailProperties.Transport.CAIXA_DE_TESTE, "fastPay <x@y.invalid>",
            "https://hml-fastpay.malyah.tech", List.of(), ZoneId.of("America/Sao_Paulo"), false,
            new MailProperties.Smtp(null, 587, null, null, true, Duration.ofSeconds(10)));
    final PublicLinks links = new PublicLinks(props);
    final EmailTemplates modelos = new EmailTemplates(props);

    @Test
    void linksUsamOEnderecoPublicoEOTokenNoFragmento() {
        assertThat(links.passwordReset(TOKEN)).isEqualTo("https://hml-fastpay.malyah.tech/redefinir-senha#token=" + TOKEN);
        assertThat(links.invitation(TOKEN)).isEqualTo("https://hml-fastpay.malyah.tech/convite#token=" + TOKEN);
        assertThatThrownBy(() -> links.invitation("curto")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.invitation(TOKEN + "&x=1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conviteDizQuemConvidouOndePapelEPrazoNoHorarioDeBrasilia() {
        Instant vence = Instant.parse("2026-10-15T18:30:00Z");

        EmailMessage convite = modelos.invitation("dono@exemplo.com", "Admin fastPay", "Bar da Praia",
                "administrador do estabelecimento", links.invitation(TOKEN), vence);

        assertThat(convite.to()).isEqualTo("dono@exemplo.com");
        assertThat(convite.subject()).isEqualTo("Convite para Bar da Praia no fastPay");
        assertThat(convite.body()).contains("Admin fastPay convidou você para participar de Bar da Praia",
                "como administrador do estabelecimento", "até 15/10/2026 às 15:30 (horário de Brasília)",
                "só pode ser usado uma vez", "/convite#token=" + TOKEN, "Ninguém mais conhece essa senha");
    }

    @Test
    void redefinicaoEAvisoDeMfa() {
        EmailMessage reset = modelos.passwordReset("ana@exemplo.com", "Ana", links.passwordReset(TOKEN),
                Instant.parse("2026-10-13T15:00:00Z"));
        EmailMessage aviso = modelos.mfaReplaced("ana@exemplo.com", "Ana", Instant.parse("2026-10-13T15:00:00Z"));

        assertThat(reset.body()).contains("Olá, Ana", "30 minutos, até 13/10/2026 às 12:00 (horário de Brasília), e só pode ser usado uma vez",
                "/redefinir-senha#token=" + TOKEN);
        assertThat(aviso.subject()).isEqualTo("Verificação em duas etapas reconfigurada no fastPay");
        assertThat(aviso.body()).contains("Em 13/10/2026 às 12:00", "deixaram de valer", "sessões abertas");
        EmailMessage recuperacao = modelos.mfaReset("ana@exemplo.com", "Ana", Instant.parse("2026-10-13T15:00:00Z"));
        assertThat(recuperacao.subject()).isEqualTo("Acesso à sua conta redefinido no fastPay");
        assertThat(recuperacao.body()).contains("Olá, Ana", "Em 13/10/2026 às 12:00", "confirmar a sua identidade",
                "deixaram de valer", "não traz link").doesNotContain("token");
    }
}
