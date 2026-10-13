package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    /** Segredo dos vetores de teste SHA1 da RFC 6238 (apêndice B). */
    static final byte[] RFC = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({ "59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924",
            "2000000000, 279037", "20000000000, 353130" })
    void codigosDaRfc6238ComSeisDigitos(long segundos, String esperado) {
        assertThat(Totp.code(RFC, Totp.step(Instant.ofEpochSecond(segundos)))).isEqualTo(esperado);
    }

    @Test
    void aceitaUmPassoDeCadaLadoENaoDois() {
        Instant agora = Instant.ofEpochSecond(1_000_000_000L);
        long passo = Totp.step(agora);

        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo - 1), agora, -1)).hasValue(passo - 1);
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo), agora, -1)).hasValue(passo);
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo + 1), agora, -1)).hasValue(passo + 1);
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo - 2), agora, -1)).isEmpty();
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo + 2), agora, -1)).isEmpty();
    }

    @Test
    void passoJaUsadoOuAnteriorNaoValeMais() {
        Instant agora = Instant.ofEpochSecond(1_000_000_000L);
        long passo = Totp.step(agora);

        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo), agora, passo)).isEmpty();
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo - 1), agora, passo)).isEmpty();
        assertThat(Totp.matchingStep(RFC, Totp.code(RFC, passo + 1), agora, passo)).hasValue(passo + 1);
    }

    @Test
    void codigoComEspacoValeEFormatoErradoNao() {
        Instant agora = Instant.ofEpochSecond(59);
        assertThat(Totp.matchingStep(RFC, "287 082", agora, -1)).isPresent();
        assertThat(Totp.normalize("28708")).isNull();
        assertThat(Totp.normalize("2870821")).isNull();
        assertThat(Totp.normalize("28708a")).isNull();
        assertThat(Totp.normalize(null)).isNull();
        assertThat(Totp.matchingStep(RFC, "abc", agora, -1)).isEmpty();
    }

    @Test
    void segredoNovoTem160BitsEEnderecoParaOAplicativo() {
        byte[] segredo = Totp.newSecret();
        assertThat(segredo).hasSize(20).isNotEqualTo(Totp.newSecret());

        assertThat(Totp.provisioningUri("fastPay", "ana@bar.com", RFC)).isEqualTo(
                "otpauth://totp/fastPay:ana%40bar.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=fastPay"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
