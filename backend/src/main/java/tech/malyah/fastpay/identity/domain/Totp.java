package tech.malyah.fastpay.identity.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP da RFC 6238 com os parâmetros que os aplicativos autenticadores aceitam sem configuração: HMAC-SHA1,
 * 6 dígitos, passo de 30 segundos. Aceita o passo atual e um de cada lado (relógio do celular adiantado ou
 * atrasado até 30 s), nunca um passo igual ou anterior ao último já usado: o mesmo código não vale duas
 * vezes, nem dentro da janela.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Passos aceitos antes e depois do atual. */
    public static final int WINDOW = 1;
    static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MODULUS = 1_000_000;

    private Totp() {
    }

    /** Segredo novo de 160 bits (tamanho recomendado pela RFC 4226 para HMAC-SHA1). */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** Código do passo, com zeros à esquerda. */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % MODULUS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 indisponível", e);
        }
    }

    /**
     * Passo em que o código vale, dentro da janela e depois de {@code lastUsedStep}; vazio se não vale.
     * Quem chama grava o passo devolvido como o novo último usado, de forma atômica.
     *
     * @param lastUsedStep último passo aceito para este segredo, ou {@code -1} se nenhum
     */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now, long lastUsedStep) {
        Objects.requireNonNull(secret, "secret");
        String digits = normalize(code);
        if (digits == null) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long candidate = current - WINDOW; candidate <= current + WINDOW; candidate++) {
            boolean same = MessageDigest.isEqual(code(secret, candidate).getBytes(), digits.getBytes());
            if (same && candidate > lastUsedStep) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    /** Só dígitos; aceita espaços digitados no meio ("123 456"). Nulo se não parece um código TOTP. */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String digits = code.replace(" ", "");
        return digits.length() == DIGITS && digits.chars().allMatch(c -> c >= '0' && c <= '9') ? digits : null;
    }

    /**
     * Endereço {@code otpauth://} para o aplicativo autenticador (formato do Google Authenticator, aceito
     * pelos demais). Rótulo e emissor sem caracteres especiais além do que a codificação de URL trata.
     */
    public static String provisioningUri(String issuer, String account, byte[] secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
