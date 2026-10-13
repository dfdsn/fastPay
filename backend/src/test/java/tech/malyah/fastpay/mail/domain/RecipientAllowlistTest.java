package tech.malyah.fastpay.mail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class RecipientAllowlistTest {

    @Test
    void listaVaziaNaoRestringe() {
        RecipientAllowlist lista = RecipientAllowlist.of(List.of(" ", ""));

        assertThat(lista.restricted()).isFalse();
        assertThat(lista.allows("qualquer@lugar.com")).isTrue();
    }

    @Test
    void enderecoExatoOuDominioInteiroSemDiferencaDeCaixa() {
        RecipientAllowlist lista = RecipientAllowlist.of(List.of("Diego.Teste@Gmail.com", " @equipe-hml.malyah.tech "));

        assertThat(lista.restricted()).isTrue();
        assertThat(lista.allows("diego.teste@gmail.com")).isTrue();
        assertThat(lista.allows("garcom1@EQUIPE-HML.malyah.tech")).isTrue();
        assertThat(lista.allows("outra.pessoa@gmail.com")).isFalse();
        // Subdomínio ou sufixo parecido não passa.
        assertThat(lista.allows("x@sub.equipe-hml.malyah.tech")).isFalse();
        assertThat(lista.allows("x@falsoequipe-hml.malyah.tech")).isFalse();
        assertThat(lista.allows("sem-arroba")).isFalse();
        assertThat(lista.allows(null)).isFalse();
    }

    @Test
    void entradaMalFormadaImpedeASubida() {
        assertThatThrownBy(() -> RecipientAllowlist.of(List.of("gmail.com")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("@dominio");
        assertThatThrownBy(() -> RecipientAllowlist.of(List.of("a@b@c")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipientAllowlist.of(List.of("pessoa@")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
