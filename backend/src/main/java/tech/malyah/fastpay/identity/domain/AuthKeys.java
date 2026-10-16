package tech.malyah.fastpay.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Normalização e chaves derivadas usadas na autenticação. Tokens e chaves de limitação são guardados só
 * como SHA-256; o token em claro existe apenas no link enviado por e-mail.
 */
public final class AuthKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private AuthKeys() {
    }

    /** E-mail como gravado em {@code user_identity}: sem espaços nas pontas e em minúsculas. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /** E-mail já normalizado com forma plausível: uma arroba, sem espaços, até 254 caracteres. */
    public static boolean plausibleEmail(String normalized) {
        int at = normalized.indexOf('@');
        return normalized.length() >= 3 && normalized.length() <= 254 && at > 0 && at == normalized.lastIndexOf('@')
                && at < normalized.length() - 1 && normalized.chars().noneMatch(Character::isWhitespace);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Token aleatório de 256 bits, seguro para URL. */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
