package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class Base32Test {

    /** Vetores da RFC 4648 §10, sem preenchimento. */
    @ParameterizedTest
    @CsvSource({ "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI" })
    void vetoresDaRfc4648(String texto, String esperado) {
        byte[] bytes = texto.getBytes(StandardCharsets.US_ASCII);
        assertThat(Base32.encode(bytes)).isEqualTo(esperado);
        assertThat(Base32.decode(esperado)).isEqualTo(bytes);
    }

    @Test
    void aceitaComoAPessoaCopiaDaTela() {
        assertThat(Base32.decode("mzxw 6ytb-oi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }
}
