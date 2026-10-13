package tech.malyah.fastpay.identity.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Cifra do segredo TOTP (especificação §5: chave fora do banco). AES-256-GCM com IV aleatório e o id da
 * identidade como dado associado, para que o texto cifrado de uma pessoa não sirva copiado para outra.
 * Formato guardado: {@code v1:} + Base64 URL de IV e texto cifrado. O prefixo de versão permite trocar a
 * chave no futuro decifrando com a antiga e cifrando de novo.
 *
 * <p>A chave vem de {@code fastpay.auth.mfa-encryption-key} (variável {@code MFA_ENCRYPTION_KEY}, 32 bytes
 * em Base64). Sem chave válida a aplicação não sobe: não existe chave padrão.
 */
@Component
public class MfaSecretCipher {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    MfaSecretCipher(AuthProperties properties) {
        this.key = new SecretKeySpec(decodeKey(properties.mfaEncryptionKey()), "AES");
    }

    static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Defina fastpay.auth.mfa-encryption-key (MFA_ENCRYPTION_KEY): "
                    + "32 bytes aleatórios em Base64, guardados fora do banco.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("fastpay.auth.mfa-encryption-key não está em Base64.");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("fastpay.auth.mfa-encryption-key deve ter 32 bytes (AES-256).");
        }
        return bytes;
    }

    public String encrypt(UUID identityId, byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(identityId));
            byte[] sealed = cipher.doFinal(secret);
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o segredo do autenticador", e);
        }
    }

    /** @throws IllegalStateException texto adulterado, de outra identidade ou cifrado com outra chave */
    public byte[] decrypt(UUID identityId, String stored) {
        if (stored == null || !stored.startsWith(VERSION)) {
            throw new IllegalStateException("Segredo do autenticador em formato desconhecido");
        }
        try {
            byte[] in = Base64.getUrlDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(associatedData(identityId));
            return cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Segredo do autenticador não pôde ser decifrado", e);
        }
    }

    private static byte[] associatedData(UUID identityId) {
        return ("fastpay-mfa:" + identityId).getBytes(StandardCharsets.UTF_8);
    }
}
