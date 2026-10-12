package tech.malyah.fastpay.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Política de senha da equipe (H08; P-07 aprovada por Diego em 12/10/2026), baseada no NIST SP
 * 800-63B: comprimento mínimo configurável (padrão 12), sem regras de composição nem troca periódica,
 * recusa de senhas muito comuns e de senha que contenha o e-mail. O máximo de 64 caracteres (e 72 bytes) vem do algoritmo de hash escolhido (bcrypt).
 */
public final class PasswordPolicy {

    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;
    static final int ABSOLUTE_MIN_LENGTH = 8;

    /** Lista local mínima; consulta a bases de senhas vazadas depende de decisão (P-07). */
    private static final Set<String> COMMON = Set.of(
            "123456789012", "1234567890123", "12345678901234", "123456789abc", "1q2w3e4r5t6y",
            "qwertyuiopas", "qwerty123456", "senha1234567", "senha12345678", "senhasenha12", "password1234",
            "passwordpassword", "abcdefghijkl", "abc123abc123", "iloveyou1234", "admin1234567",
            "administrador", "fastpay12345", "fastpayfastpay", "mudar1234567", "trocar123456",
            "000000000000", "111111111111", "aaaaaaaaaaaa", "brasil123456", "flamengo1234");

    private final int minLength;

    public PasswordPolicy(int minLength) {
        if (minLength < ABSOLUTE_MIN_LENGTH || minLength > MAX_LENGTH) {
            throw new IllegalArgumentException("Comprimento mínimo de senha deve estar entre "
                    + ABSOLUTE_MIN_LENGTH + " e " + MAX_LENGTH);
        }
        this.minLength = minLength;
    }

    public int minLength() {
        return minLength;
    }

    /** Motivo da recusa, em português para a UI; vazio se a senha é aceita. */
    public Optional<String> violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of("Informe a nova senha.");
        }
        int length = password.codePointCount(0, password.length());
        if (length < minLength) {
            return Optional.of("Use pelo menos " + minLength + " caracteres.");
        }
        if (length > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("Use no máximo " + MAX_LENGTH + " caracteres.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || lower.codePoints().distinct().count() <= 2) {
            return Optional.of("Esta senha é muito comum. Escolha outra.");
        }
        if (email != null) {
            String normalized = email.toLowerCase(Locale.ROOT);
            int at = normalized.indexOf('@');
            String local = at > 0 ? normalized.substring(0, at) : normalized;
            if (lower.contains(normalized) || (local.length() >= 4 && lower.contains(local))) {
                return Optional.of("Não use seu e-mail na senha.");
            }
        }
        return Optional.empty();
    }
}
