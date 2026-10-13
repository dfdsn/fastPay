package tech.malyah.fastpay.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MfaSecretCipherTest {

    static String chave() {
        return Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32));
    }

    static MfaSecretCipher cifra(String chave) {
        return new MfaSecretCipher(new AuthProperties(12, 12, 5, 20, Duration.ofMinutes(15),
                3, 10, 10, Duration.ofHours(1), true, chave, "fastPay", 5, Duration.ofMinutes(10)));
    }

    @Test
    void cifraEDecifraSoParaAMesmaIdentidadeEChave() {
        String chave = chave();
        MfaSecretCipher cifra = cifra(chave);
        UUID ana = UUID.randomUUID();
        byte[] segredo = "segredo de vinte by".getBytes();

        String guardado = cifra.encrypt(ana, segredo);

        assertThat(guardado).startsWith("v1:").isNotEqualTo(cifra.encrypt(ana, segredo));
        assertThat(cifra.decrypt(ana, guardado)).isEqualTo(segredo);
        assertThat(cifra(chave).decrypt(ana, guardado)).isEqualTo(segredo);
        assertThatThrownBy(() -> cifra.decrypt(UUID.randomUUID(), guardado)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cifra(chave()).decrypt(ana, guardado)).isInstanceOf(IllegalStateException.class);
        String adulterado = guardado.substring(0, guardado.length() - 2) + (guardado.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> cifra.decrypt(ana, adulterado)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cifra.decrypt(ana, "v0:abc")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void semChaveValidaNaoSobe() {
        assertThatThrownBy(() -> cifra(null)).hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> cifra(" ")).hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> cifra("não é base64!")).hasMessageContaining("Base64");
        assertThatThrownBy(() -> cifra(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 bytes");
    }
}
