package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Proposta de política de senha (P-07, NIST SP 800-63B), com mensagens em português para a UI. */
class PasswordPolicyTest {

    PasswordPolicy policy = new PasswordPolicy(12);
    String email = "ana.souza@bardapraia.com.br";

    @Test
    void aceitaFraseLongaSemExigirComposicao() {
        assertThat(policy.violation("tres palavras quaisquer", email)).isEmpty();
        assertThat(policy.violation("ção ção ção ção ção 1", email)).isEmpty();
    }

    @Test
    void exigeComprimentoMinimoContadoEmCaracteres() {
        assertThat(policy.violation("onze caract", email)).hasValue("Use pelo menos 12 caracteres.");
        assertThat(policy.violation("doze caracte", email)).isEmpty();
        assertThat(policy.violation(null, email)).hasValue("Informe a nova senha.");
        assertThat(policy.violation("            ", email)).hasValue("Informe a nova senha.");
    }

    @Test
    void limitaMaximoPorCaracteresEPorBytesDoHash() {
        String sessentaEQuatro = "abcdefgh".repeat(8);
        assertThat(policy.violation(sessentaEQuatro, email)).isEmpty();
        assertThat(policy.violation(sessentaEQuatro + "i", email)).hasValue("Use no máximo 64 caracteres.");
        // 40 caracteres, 80 bytes em UTF-8: passaria do limite do bcrypt.
        assertThat(policy.violation("çã".repeat(20), email)).hasValue("Use no máximo 64 caracteres.");
    }

    @Test
    void recusaSenhaComumOuRepetitiva() {
        assertThat(policy.violation("123456789012", email)).hasValue("Esta senha é muito comum. Escolha outra.");
        assertThat(policy.violation("SENHA1234567", email)).hasValue("Esta senha é muito comum. Escolha outra.");
        assertThat(policy.violation("abababababababab", email)).hasValue("Esta senha é muito comum. Escolha outra.");
    }

    @Test
    void recusaSenhaComOEmail() {
        assertThat(policy.violation("Ana.Souza2026!", email)).hasValue("Não use seu e-mail na senha.");
        assertThat(policy.violation("x" + email, email)).hasValue("Não use seu e-mail na senha.");
        assertThat(policy.violation("ana é legal demais", "ana@x.com")).isEmpty();
        assertThat(policy.violation("uma frase qualquer", null)).isEmpty();
    }

    @Test
    void minimoConfiguravelDentroDeLimites() {
        assertThat(new PasswordPolicy(15).violation("catorze caract", email)).hasValue("Use pelo menos 15 caracteres.");
        assertThatThrownBy(() -> new PasswordPolicy(7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordPolicy(65)).isInstanceOf(IllegalArgumentException.class);
    }
}
