package tech.malyah.fastpay.orders.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Regras puras do envio de pedido (H16, FR-14–FR-16, D-03, D-13, especificação §4 e §7). O rascunho é montado no
 * aparelho; aqui chegam as linhas que a pessoa confirmou com o preço que ela viu. Normaliza e valida o que foi
 * digitado, calcula a impressão digital do conteúdo (para a mesma chave de operação não servir a outro pedido) e
 * decide se o pedido precisa de revisão: produto esgotado ou fora do catálogo deixa o pedido inteiro no rascunho;
 * preço diferente do visto exige reconfirmação. Nada é enviado em parte.
 */
public final class OrderRules {

    /** Linhas por envio (D-13). */
    public static final int MAX_LINES = 50;
    /** Unidades por linha (D-13, especificação §7). */
    public static final int MAX_QUANTITY = 99;
    /** Observação para a produção, sem cobrança (FR-13); proposta T-03 da H16. */
    public static final int NOTE_MAX = 140;

    /** Preço visto no rascunho: reais com até duas casas, ponto decimal, como a API devolve. */
    private static final Pattern PRICE = Pattern.compile("^\\d{1,5}(\\.\\d{1,2})?$");

    public record Invalid(String field, String message) {
    }

    public sealed interface Checked<T> {
    }

    public record Valid<T>(T value) implements Checked<T> {
    }

    public record Rejected<T>(Invalid invalid) implements Checked<T> {
    }

    /** Linha como chegou na requisição. */
    public record RawLine(UUID productId, Integer quantity, String note, String expectedPrice) {
    }

    /** Linha validada: {@code note} vazio vira nulo; {@code expectedPrice} com duas casas. */
    public record DraftLine(UUID productId, int quantity, String note, BigDecimal expectedPrice) {
    }

    /** Produto como está no catálogo no momento do envio, travado para leitura. */
    public record CurrentProduct(UUID id, BigDecimal price, boolean available) {
    }

    /** Preço que mudou desde que a pessoa montou o rascunho. */
    public record PriceChange(UUID productId, BigDecimal seenPrice, BigDecimal currentPrice) {
    }

    /** Pedido que não pode sair como está; vazio nas duas listas quando pode. */
    public record Review(List<UUID> unavailable, List<PriceChange> priceChanges) {
        public boolean needed() {
            return !unavailable.isEmpty() || !priceChanges.isEmpty();
        }
    }

    private OrderRules() {
    }

    public static Checked<List<DraftLine>> lines(List<RawLine> raw) {
        if (raw == null || raw.isEmpty()) {
            return reject("lines", "O pedido está vazio. Inclua ao menos um item.");
        }
        if (raw.size() > MAX_LINES) {
            return reject("lines", "Um envio tem no máximo %d linhas. Envie o restante em outro pedido."
                    .formatted(MAX_LINES));
        }
        List<DraftLine> valid = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RawLine line = raw.get(i);
            String at = "lines[" + i + "]";
            if (line == null || line.productId() == null) {
                return reject(at + ".productId", "Item %d sem produto. Remova e inclua de novo.".formatted(i + 1));
            }
            Integer q = line.quantity();
            if (q == null || q < 1 || q > MAX_QUANTITY) {
                return reject(at + ".quantity", "Item %d: a quantidade vai de 1 a %d.".formatted(i + 1, MAX_QUANTITY));
            }
            String note = line.note() == null ? "" : line.note().strip();
            if (note.length() > NOTE_MAX || note.chars().anyMatch(Character::isISOControl)) {
                return reject(at + ".note", "Item %d: a observação tem até %d caracteres, numa linha só."
                        .formatted(i + 1, NOTE_MAX));
            }
            String price = line.expectedPrice() == null ? "" : line.expectedPrice().strip();
            if (!PRICE.matcher(price).matches()) {
                return reject(at + ".expectedPrice", "Item %d sem o preço conferido. Atualize o catálogo e confira."
                        .formatted(i + 1));
            }
            valid.add(new DraftLine(line.productId(), q, note.isEmpty() ? null : note,
                    new BigDecimal(price).setScale(2, RoundingMode.UNNECESSARY)));
        }
        return new Valid<>(List.copyOf(valid));
    }

    /**
     * Confere o pedido com o catálogo do momento (FR-14, FR-16, CA-05). Produto ausente (arquivado, de outra unidade)
     * ou esgotado entra em {@code unavailable}; preço diferente do visto, em {@code priceChanges}. Cada produto aparece
     * uma vez em cada lista, na ordem do pedido.
     */
    public static Review review(List<DraftLine> lines, Map<UUID, CurrentProduct> catalog) {
        Set<UUID> unavailable = new LinkedHashSet<>();
        List<PriceChange> changes = new ArrayList<>();
        Set<UUID> changed = new LinkedHashSet<>();
        for (DraftLine line : lines) {
            CurrentProduct product = catalog.get(line.productId());
            if (product == null || !product.available()) {
                unavailable.add(line.productId());
            } else if (product.price().compareTo(line.expectedPrice()) != 0 && changed.add(line.productId())) {
                changes.add(new PriceChange(line.productId(), line.expectedPrice(), money(product.price())));
            }
        }
        return new Review(List.copyOf(unavailable), List.copyOf(changes));
    }

    /**
     * Impressão digital do conteúdo do envio: comanda e linhas na ordem (produto, quantidade, observação, preço
     * conferido). A mesma chave de operação com outra impressão é outro pedido e é recusada.
     */
    public static String fingerprint(UUID tabId, List<DraftLine> lines) {
        StringBuilder canonical = new StringBuilder("v1\n").append(tabId).append('\n');
        for (DraftLine line : lines) {
            canonical.append(line.productId()).append('|').append(line.quantity()).append('|')
                    .append(line.note() == null ? "" : line.note().replace("\\", "\\\\").replace("|", "\\|"))
                    .append('|').append(line.expectedPrice().toPlainString()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Valor em reais com duas casas, para JSON como texto (especificação §7). */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static <T> Checked<T> reject(String field, String message) {
        return new Rejected<>(new Invalid(field, message));
    }
}
