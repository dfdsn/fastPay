package tech.malyah.fastpay.establishments.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CNPJ informado no cadastro (FR-01). Aceita o formato numérico e o alfanumérico da Receita Federal (12
 * caracteres de base em 0-9/A-Z e 2 dígitos verificadores), com ou sem pontuação. Cada caractere vale o código
 * ASCII menos 48 no cálculo dos verificadores (módulo 11), o que dá o mesmo resultado do formato numérico. Só
 * confere a forma: não diz que a empresa existe nem que pode receber pelo PSP (P-02).
 */
public final class Cnpj {

    private static final Pattern PUNCTUATION = Pattern.compile("[.\\-/\\s]");
    private static final Pattern NORMALIZED = Pattern.compile("^[0-9A-Z]{12}[0-9]{2}$");
    private static final int[] WEIGHTS_FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] WEIGHTS_SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpj() {
    }

    /** CNPJ sem pontuação e em maiúsculas, se válido. */
    public static Optional<String> normalize(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = PUNCTUATION.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        if (!NORMALIZED.matcher(candidate).matches() || candidate.chars().allMatch(c -> c == candidate.charAt(0))) {
            return Optional.empty();
        }
        int first = checkDigit(candidate, WEIGHTS_FIRST);
        int second = checkDigit(candidate, WEIGHTS_SECOND);
        return candidate.charAt(12) - '0' == first && candidate.charAt(13) - '0' == second
                ? Optional.of(candidate) : Optional.empty();
    }

    private static int checkDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
