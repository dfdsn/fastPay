package tech.malyah.fastpay.bills.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

import tech.malyah.fastpay.establishments.ServiceBase;

/**
 * Composição da conta (H22, PRD §10.2, FR-28–FR-30, FR-68, especificação §7). Sem estado: recebe o consumo, o desconto
 * ativo, o couvert e o serviço capturados e devolve C, D, K, B, S e o total.
 *
 * <ul>
 * <li>C = soma dos lançamentos ativos de consumo (preço do envio × unidades na comanda).</li>
 * <li>D = desconto sobre C, arredondado HALF_UP em 2 casas: fixo efetivo = min(valor, C); percentual = C × p / 100.
 * Sempre 0 ≤ D ≤ C (D-09).</li>
 * <li>K = couvert ativo. Nunca recebe desconto.</li>
 * <li>B = C ou C − D (com o D já arredondado), conforme a base do serviço, mais K só se configurado.</li>
 * <li>S = B × percentual / 100, HALF_UP em 2 casas; zero com o serviço desabilitado ou retirado pelo cliente.</li>
 * <li>Total = C − D + K + S, nunca negativo.</li>
 * </ul>
 */
public final class BillRules {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Até 7 dígitos inteiros e 2 casas (até R$ 9.999.999,99); mais casas são recusadas, não arredondadas. */
    private static final Pattern MONEY = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");
    /** Até 3 dígitos inteiros e 4 casas (especificação §7). */
    private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(\\.\\d{1,4})?");

    private BillRules() {
    }

    public enum Kind {
        /** Valor fixo em reais, limitado ao consumo atual. */
        FIXO,
        /** Percentual sobre o consumo, com até 4 casas. */
        PERCENTUAL
    }

    /** Desconto ativo: {@code value} em reais (fixo, 2 casas) ou percentual (4 casas). */
    public record Discount(Kind kind, BigDecimal value) {
    }

    /** Serviço capturado na abertura da comanda e a escolha do cliente. */
    public record Service(boolean enabled, BigDecimal percent, ServiceBase base, boolean includesCouvert,
            boolean waived) {
    }

    /** Valores em reais com 2 casas. {@code serviceBase}: B, a base sobre a qual o serviço é calculado. */
    public record Composition(BigDecimal consumption, BigDecimal discount, BigDecimal couvert, BigDecimal serviceBase,
            BigDecimal service, BigDecimal total) {
    }

    public static Composition compose(BigDecimal consumption, Discount discount, BigDecimal couvert,
            Service service) {
        BigDecimal c = money(consumption);
        BigDecimal k = money(couvert == null ? BigDecimal.ZERO : couvert);
        BigDecimal d = discount == null ? zero() : discountAmount(c, discount);
        BigDecimal b = service.base() == ServiceBase.APOS_DESCONTO ? c.subtract(d) : c;
        if (service.includesCouvert()) {
            b = b.add(k);
        }
        BigDecimal s = service.enabled() && !service.waived()
                ? b.multiply(service.percent()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : zero();
        BigDecimal total = c.subtract(d).add(k).add(s);
        if (total.signum() < 0) {
            throw new IllegalStateException("Total negativo: " + total);
        }
        return new Composition(c, d, k, b, s, total);
    }

    /** D efetivo sobre o consumo C, HALF_UP 2 casas, nunca acima de C (D-09). */
    public static BigDecimal discountAmount(BigDecimal consumption, Discount discount) {
        BigDecimal c = money(consumption);
        BigDecimal d = switch (discount.kind()) {
            case FIXO -> money(discount.value()).min(c);
            case PERCENTUAL -> c.multiply(discount.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        };
        return d.min(c).max(zero());
    }

    /**
     * O desconto D sobre o consumo C passa do limite (percentual do consumo, P-17)? Conta exata, sem dividir:
     * D × 100 > limite × C. Desconto zero nunca passa.
     */
    public static boolean exceeds(BigDecimal discount, BigDecimal consumption, BigDecimal limitPercent) {
        if (discount.signum() <= 0) {
            return false;
        }
        return discount.multiply(HUNDRED).compareTo(limitPercent.multiply(consumption)) > 0;
    }

    /**
     * O desconto passa da autoridade (limite de quem concedeu ou revisou, P-17, FR-68)? Percentual compara o
     * percentual concedido, que não muda com o consumo (o centavo do arredondamento de D não conta como excesso).
     * Em reais compara o D efetivo com o consumo atual: é aqui que uma redução de consumo pode passar do limite.
     */
    public static boolean needsReview(Discount discount, BigDecimal effectiveDiscount, BigDecimal consumption,
            BigDecimal authorityPercent) {
        return switch (discount.kind()) {
            case PERCENTUAL -> discount.value().compareTo(authorityPercent) > 0;
            case FIXO -> exceeds(effectiveDiscount, consumption, authorityPercent);
        };
    }

    /** Percentual efetivo do desconto sobre o consumo, com 2 casas, só para exibição. */
    public static BigDecimal effectivePercent(BigDecimal discount, BigDecimal consumption) {
        if (consumption.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return discount.multiply(HUNDRED).divide(consumption, 2, RoundingMode.HALF_UP);
    }

    /** Valor do desconto digitado, já no formato do tipo, ou o problema em português. */
    public sealed interface Parsed {
    }

    public record Valid(Discount discount) implements Parsed {
    }

    public record Rejected(String field, String message) implements Parsed {
    }

    public static Parsed discount(String kind, String value) {
        Kind k;
        try {
            k = kind == null ? null : Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            k = null;
        }
        if (k == null) {
            return new Rejected("kind", "Escolha desconto em reais ou percentual.");
        }
        String v = value == null ? "" : value.trim().replace(',', '.');
        return switch (k) {
            case FIXO -> {
                if (!MONEY.matcher(v).matches()) {
                    yield new Rejected("value", "Informe o valor em reais com até 2 casas, como 10.50.");
                }
                BigDecimal amount = new BigDecimal(v).setScale(2);
                yield amount.signum() > 0 ? new Valid(new Discount(k, amount))
                        : new Rejected("value", "O desconto precisa ser maior que zero.");
            }
            case PERCENTUAL -> {
                if (!PERCENT.matcher(v).matches()) {
                    yield new Rejected("value", "Informe o percentual com até 4 casas, como 10 ou 12.5.");
                }
                BigDecimal percent = new BigDecimal(v).setScale(4);
                if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                    yield new Rejected("value", "O percentual precisa ser maior que 0 e no máximo 100.");
                }
                yield new Valid(new Discount(k, percent));
            }
        };
    }

    /** Motivo de 5 a 300 caracteres, sem espaços nas pontas; vazio se não serve. */
    public static Optional<String> reason(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        return text.length() >= 5 && text.length() <= 300 ? Optional.of(text) : Optional.empty();
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
