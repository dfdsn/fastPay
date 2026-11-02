package tech.malyah.fastpay.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regras de preenchimento do catálogo (H13, FR-13, FR-18). Puras: normalizam e validam o que a pessoa digitou,
 * com mensagem em português para o campo. Tamanhos e versões são produtos distintos; sem estoque, adicionais
 * pagos nem combos.
 */
public final class CatalogRules {

    public static final int NAME_MAX = 120;
    public static final int CATEGORY_MAX = 60;
    public static final int DESCRIPTION_MAX = 500;
    public static final int SECTOR_NAME_MAX = 60;
    /** Maior preço aceito (limite técnico da coluna, proposta T-03): R$ 99.999,99. */
    public static final BigDecimal PRICE_MAX = new BigDecimal("99999.99");

    // "12", "12,5", "12,50", "1.234,50", "1234.50": até duas casas.
    private static final Pattern BRAZILIAN = Pattern.compile("\\d{1,3}(\\.\\d{3})*(,\\d{1,2})?|\\d+(,\\d{1,2})?");
    private static final Pattern PLAIN = Pattern.compile("\\d+(\\.\\d{1,2})?");

    /** Campo inválido e o que dizer na tela. */
    public record Invalid(String field, String message) {
    }

    /** Produto já normalizado. */
    public record ProductFields(String name, String category, String description, BigDecimal price) {
    }

    public sealed interface Checked<T> {
    }

    public record Valid<T>(T value) implements Checked<T> {
    }

    public record Rejected<T>(Invalid invalid) implements Checked<T> {
    }

    private CatalogRules() {
    }

    public static Checked<ProductFields> product(String name, String category, String description, String price) {
        String n = collapse(name);
        if (n.isEmpty() || n.length() > NAME_MAX) {
            return reject("name", "Informe o nome do produto, com até %d caracteres.".formatted(NAME_MAX));
        }
        String c = collapse(category);
        if (c.isEmpty() || c.length() > CATEGORY_MAX) {
            return reject("category", "Informe a categoria, com até %d caracteres.".formatted(CATEGORY_MAX));
        }
        String d = description == null ? "" : description.strip();
        if (d.length() > DESCRIPTION_MAX) {
            return reject("description", "A descrição pode ter até %d caracteres.".formatted(DESCRIPTION_MAX));
        }
        Optional<BigDecimal> p = price(price);
        if (p.isEmpty()) {
            return reject("price", "Informe o preço em reais, maior que zero, com até duas casas decimais (ex.: 12,50).");
        }
        return new Valid<>(new ProductFields(n, c, d.isEmpty() ? null : d, p.get()));
    }

    public static Checked<String> sectorName(String name) {
        String n = collapse(name);
        if (n.isEmpty() || n.length() > SECTOR_NAME_MAX) {
            return reject("name", "Informe o nome do setor, com até %d caracteres.".formatted(SECTOR_NAME_MAX));
        }
        return new Valid<>(n);
    }

    /**
     * Preço em reais, maior que zero e até {@link #PRICE_MAX}, com no máximo duas casas: aceita vírgula ou ponto
     * decimal e ponto de milhar no formato brasileiro. Nunca arredonda o que foi digitado.
     */
    public static Optional<BigDecimal> price(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (s.startsWith("R$")) {
            s = s.substring(2).strip();
        }
        String normalized;
        if (PLAIN.matcher(s).matches()) {
            normalized = s;
        } else if (BRAZILIAN.matcher(s).matches()) {
            normalized = s.replace(".", "").replace(',', '.');
        } else {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY);
        if (value.signum() <= 0 || value.compareTo(PRICE_MAX) > 0) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** Tira espaços das pontas e junta espaços repetidos (nomes iguais não se distinguem por espaço). */
    static String collapse(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static <T> Checked<T> reject(String field, String message) {
        return new Rejected<>(new Invalid(field, message));
    }
}
