package tech.malyah.fastpay.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.orders.domain.OrderRules.CurrentProduct;
import tech.malyah.fastpay.orders.domain.OrderRules.DraftLine;
import tech.malyah.fastpay.orders.domain.OrderRules.RawLine;
import tech.malyah.fastpay.orders.domain.OrderRules.Rejected;
import tech.malyah.fastpay.orders.domain.OrderRules.Review;
import tech.malyah.fastpay.orders.domain.OrderRules.Valid;

class OrderRulesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TAB = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @Test
    void normalizaLinhasNosLimitesExatos() {
        List<DraftLine> lines = valid(List.of(new RawLine(A, 99, "  sem gelo  ", "9.9"),
                new RawLine(B, 1, "   ", "12"), new RawLine(A, 1, "x".repeat(140), "0.50")));
        assertThat(lines.get(0)).isEqualTo(new DraftLine(A, 99, "sem gelo", new BigDecimal("9.90")));
        assertThat(lines.get(1).note()).isNull();
        assertThat(lines.get(1).expectedPrice()).isEqualByComparingTo("12.00");
        assertThat(valid(Collections.nCopies(50, new RawLine(A, 1, null, "1")))).hasSize(50);
    }

    @Test
    void recusaForaDosLimitesComOCampoExato() {
        assertThat(field(null)).isEqualTo("lines");
        assertThat(field(List.of())).isEqualTo("lines");
        assertThat(field(Collections.nCopies(51, new RawLine(A, 1, null, "1")))).isEqualTo("lines");
        assertThat(field(List.of(new RawLine(A, 1, null, "1"), new RawLine(null, 1, null, "1"))))
                .isEqualTo("lines[1].productId");
        assertThat(field(List.of(new RawLine(A, 0, null, "1")))).isEqualTo("lines[0].quantity");
        assertThat(field(List.of(new RawLine(A, 100, null, "1")))).isEqualTo("lines[0].quantity");
        assertThat(field(List.of(new RawLine(A, null, null, "1")))).isEqualTo("lines[0].quantity");
        assertThat(field(List.of(new RawLine(A, 1, "x".repeat(141), "1")))).isEqualTo("lines[0].note");
        assertThat(field(List.of(new RawLine(A, 1, "a\nb", "1")))).isEqualTo("lines[0].note");
        assertThat(field(List.of(new RawLine(A, 1, null, "1.001")))).isEqualTo("lines[0].expectedPrice");
        assertThat(field(List.of(new RawLine(A, 1, null, "1,50")))).isEqualTo("lines[0].expectedPrice");
        assertThat(field(List.of(new RawLine(A, 1, null, "-1")))).isEqualTo("lines[0].expectedPrice");
        assertThat(field(List.of(new RawLine(A, 1, null, null)))).isEqualTo("lines[0].expectedPrice");
    }

    @Test
    void esgotadoOuAusenteEPrecoMudadoPedemRevisaoUmaVezPorProduto() {
        UUID ausente = UUID.randomUUID();
        List<DraftLine> lines = valid(List.of(new RawLine(A, 1, null, "10"), new RawLine(A, 2, null, "10"),
                new RawLine(B, 1, null, "5"), new RawLine(ausente, 1, null, "3")));
        Review review = OrderRules.review(lines, Map.of(A, new CurrentProduct(A, new BigDecimal("11.00"), true),
                B, new CurrentProduct(B, new BigDecimal("5.00"), false)));
        assertThat(review.needed()).isTrue();
        assertThat(review.unavailable()).containsExactly(B, ausente);
        assertThat(review.priceChanges()).containsExactly(
                new OrderRules.PriceChange(A, new BigDecimal("10.00"), new BigDecimal("11.00")));

        Review ok = OrderRules.review(lines.subList(0, 2), Map.of(A, new CurrentProduct(A, new BigDecimal("10"), true)));
        assertThat(ok.needed()).isFalse();
    }

    @Test
    void impressaoDigitalMudaComQualquerCampoEComAOrdem() {
        List<DraftLine> base = valid(List.of(new RawLine(A, 1, "a|b", "10"), new RawLine(B, 2, null, "5")));
        String digital = OrderRules.fingerprint(TAB, base);
        assertThat(digital).matches("[0-9a-f]{64}")
                .isEqualTo(OrderRules.fingerprint(TAB, valid(List.of(new RawLine(A, 1, " a|b ", "10.00"),
                        new RawLine(B, 2, "", "5.0")))));
        List<DraftLine> invertida = new ArrayList<>(base);
        Collections.reverse(invertida);
        assertThat(OrderRules.fingerprint(TAB, invertida)).isNotEqualTo(digital);
        assertThat(OrderRules.fingerprint(UUID.randomUUID(), base)).isNotEqualTo(digital);
        assertThat(OrderRules.fingerprint(TAB, valid(List.of(new RawLine(A, 1, "a", "10"),
                new RawLine(B, 2, null, "5"))))).isNotEqualTo(digital);
        assertThat(OrderRules.fingerprint(TAB, valid(List.of(new RawLine(A, 1, "a|b", "10.01"),
                new RawLine(B, 2, null, "5"))))).isNotEqualTo(digital);
        // A barra na observação não se confunde com o separador.
        assertThat(OrderRules.fingerprint(TAB, valid(List.of(new RawLine(A, 1, "a", "10"))))).isNotEqualTo(
                OrderRules.fingerprint(TAB, valid(List.of(new RawLine(A, 1, "a|10", "10")))));
    }

    private static List<DraftLine> valid(List<RawLine> raw) {
        return ((Valid<List<DraftLine>>) OrderRules.lines(raw)).value();
    }

    private static String field(List<RawLine> raw) {
        return ((Rejected<List<DraftLine>>) OrderRules.lines(raw)).invalid().field();
    }
}
