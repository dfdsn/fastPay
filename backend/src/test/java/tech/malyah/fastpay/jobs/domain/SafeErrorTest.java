package tech.malyah.fastpay.jobs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.jobs.JobFailure;

class SafeErrorTest {

    @Test
    void excecaoQualquerGuardaSoONomeDaClasse() {
        String erro = SafeError.describe(new IllegalStateException("cartão 4111111111111111 de fulano@exemplo.com"));

        assertThat(erro).isEqualTo("Falha técnica (IllegalStateException).").doesNotContain("4111", "@");
    }

    @Test
    void falhaDeclaradaSeguraGuardaAMensagem() {
        assertThat(SafeError.describe(new JobFailure("  Provedor de e-mail indisponível.  ")))
                .isEqualTo("Provedor de e-mail indisponível.");
        assertThat(SafeError.describe(new JobFailure(" "))).isEqualTo("Falha técnica (JobFailure).");
    }

    @Test
    void textoLongoEhCortadoNoTamanhoDaColuna() {
        String erro = SafeError.describe(new JobFailure("x".repeat(1000)));

        assertThat(erro).hasSize(SafeError.MAX_LENGTH).endsWith("…");
    }

    @Test
    void semExcecao() {
        assertThat(SafeError.describe(null)).isEqualTo("Falha sem detalhe.");
    }
}
