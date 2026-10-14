package tech.malyah.fastpay.mail.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Destinatários permitidos (especificação §9): endereço exato ({@code pessoa@exemplo.com}) ou domínio
 * inteiro ({@code @exemplo.com}). Lista vazia permite qualquer destinatário; fora de produção o envio real
 * só sobe com a lista preenchida (ver {@code MailConfiguration}).
 */
public final class RecipientAllowlist {

    private final List<String> entries;

    private RecipientAllowlist(List<String> entries) {
        this.entries = entries;
    }

    public static RecipientAllowlist of(List<String> configured) {
        List<String> entries = configured == null ? List.of() : configured.stream()
                .filter(Objects::nonNull)
                .map(e -> e.strip().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .toList();
        for (String entry : entries) {
            int at = entry.indexOf('@');
            if (at < 0 || at != entry.lastIndexOf('@') || at == entry.length() - 1 || entry.contains(" ")) {
                throw new IllegalArgumentException(
                        "Entrada inválida na lista de destinatários permitidos (use pessoa@dominio ou @dominio): "
                                + entry);
            }
        }
        return new RecipientAllowlist(entries);
    }

    public boolean restricted() {
        return !entries.isEmpty();
    }

    public boolean allows(String recipient) {
        if (entries.isEmpty()) {
            return true;
        }
        if (recipient == null) {
            return false;
        }
        String address = recipient.strip().toLowerCase(Locale.ROOT);
        int at = address.lastIndexOf('@');
        if (at <= 0) {
            return false;
        }
        String domain = address.substring(at);
        return entries.stream().anyMatch(e -> e.startsWith("@") ? e.equals(domain) : e.equals(address));
    }
}
