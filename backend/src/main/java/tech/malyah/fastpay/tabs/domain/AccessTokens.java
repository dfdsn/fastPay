package tech.malyah.fastpay.tabs.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Tokens do QR de vínculo e do acesso avulso (H15, especificação §5): 256 bits aleatórios em Base64 URL, sem dado
 * pessoal; o banco guarda só o SHA-256. Texto fora do formato é recusado antes de consultar o banco.
 */
public final class AccessTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private AccessTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean wellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
