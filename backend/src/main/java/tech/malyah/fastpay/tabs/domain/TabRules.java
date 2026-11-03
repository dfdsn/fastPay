package tech.malyah.fastpay.tabs.domain;

/**
 * Preenchimento da comanda e da recuperação de acesso (H15, FR-07, FR-09, P-07). Puras: normalizam o que a pessoa
 * digitou e devolvem a mensagem em português para o campo. Nome repetido é permitido; nome e mesa nunca dão acesso.
 */
public final class TabRules {

    public static final int NAME_MAX = 60;
    public static final int TABLE_MAX = 20;
    public static final int REASON_MIN = 5;
    public static final int REASON_MAX = 300;

    public record Invalid(String field, String message) {
    }

    public sealed interface Checked<T> {
    }

    public record Valid<T>(T value) implements Checked<T> {
    }

    public record Rejected<T>(Invalid invalid) implements Checked<T> {
    }

    /** Como a pessoa provou, além do nome/apelido, que a comanda é dela (P-07). */
    public enum AccountCheck {
        /** Disse um item pedido na comanda. */
        ITEM_DA_CONTA,
        /** Comanda ainda sem pedido: disse a hora aproximada da abertura. */
        HORA_DA_ABERTURA
    }

    public record Recovery(AccountCheck accountCheck, String reason) {
    }

    private TabRules() {
    }

    /**
     * Só unidade ativada pelo admin fastPay (H39) abre comanda em produção; em desenvolvimento e homologação a unidade
     * em configuração também abre, para validar os fluxos (decisão de Diego em 03/11/2026).
     */
    public static boolean unitMayOpenTabs(boolean productionEnvironment, boolean unitActive) {
        return unitActive || !productionEnvironment;
    }

    public static Checked<String> name(String value) {
        String n = collapse(value);
        if (n.isEmpty() || n.length() > NAME_MAX) {
            return reject("name", "Informe o nome ou apelido do cliente, com até %d caracteres.".formatted(NAME_MAX));
        }
        return new Valid<>(n);
    }

    /** Mesa opcional: vazio vira sem mesa. */
    public static Checked<String> table(String value) {
        String t = collapse(value);
        if (t.length() > TABLE_MAX) {
            return reject("table", "A mesa tem no máximo %d caracteres.".formatted(TABLE_MAX));
        }
        return new Valid<>(t.isEmpty() ? null : t);
    }

    /**
     * Conferência presencial da recuperação (P-07, aprovada por Diego em 03/11/2026): a pessoa disse o nome/apelido
     * da comanda e um item pedido (ou a hora da abertura, se ainda não pediu nada), e quem recupera escreve o motivo.
     * Nome, mesa ou e-mail sozinhos nunca bastam.
     */
    public static Checked<Recovery> recovery(Boolean nameChecked, String accountCheck, String reason) {
        if (!Boolean.TRUE.equals(nameChecked)) {
            return reject("nameChecked", "Confirme que a pessoa disse o nome ou apelido da comanda.");
        }
        AccountCheck check;
        try {
            check = accountCheck == null ? null : AccountCheck.valueOf(accountCheck);
        } catch (IllegalArgumentException unknown) {
            check = null;
        }
        if (check == null) {
            return reject("accountCheck",
                    "Confirme que a pessoa disse um item pedido ou, sem pedidos, a hora em que a comanda foi aberta.");
        }
        String r = collapse(reason);
        if (r.length() < REASON_MIN || r.length() > REASON_MAX) {
            return reject("reason", "Escreva o motivo da recuperação, de %d a %d caracteres.".formatted(REASON_MIN,
                    REASON_MAX));
        }
        return new Valid<>(new Recovery(check, r));
    }

    private static String collapse(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static <T> Checked<T> reject(String field, String message) {
        return new Rejected<>(new Invalid(field, message));
    }
}
