package tech.malyah.fastpay.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Códigos de recuperação do MFA (especificação §5): dez por cadastro, aleatórios, de uso único, guardados
 * só como hash e mostrados uma única vez. Cada código tem 16 caracteres de um alfabeto sem ambiguidade
 * (sem 0/O, 1/I/L, U), cerca de 78 bits, mostrados em quatro grupos ({@code abcd-efgh-jkmn-pqrs}). O hash inclui
 * o id da identidade, para que o mesmo texto nunca sirva para outra pessoa.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    static final int LENGTH = 16;
    /** 30 símbolos: 16 caracteres dão cerca de 78 bits. */
    static final String ALPHABET = "23456789abcdefghjkmnpqrstvwxyz";

    private static final char[] SYMBOLS = ALPHABET.toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            StringBuilder code = new StringBuilder(LENGTH + 3);
            for (int c = 0; c < LENGTH; c++) {
                if (c > 0 && c % 4 == 0) {
                    code.append('-');
                }
                code.append(SYMBOLS[RANDOM.nextInt(SYMBOLS.length)]);
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** Sem espaços, hífens e maiúsculas; vazio se não tem o formato de um código de recuperação. */
    public static Optional<String> normalize(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String clean = code.replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (clean.length() != LENGTH || !clean.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(clean);
    }

    public static String hash(UUID identityId, String normalizedCode) {
        return AuthKeys.sha256Hex(identityId + ":" + normalizedCode);
    }
}
