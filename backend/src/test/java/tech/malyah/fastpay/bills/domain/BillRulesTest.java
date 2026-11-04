package tech.malyah.fastpay.bills.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tech.malyah.fastpay.bills.domain.BillRules.Composition;
import tech.malyah.fastpay.bills.domain.BillRules.Discount;
import tech.malyah.fastpay.bills.domain.BillRules.Kind;
import tech.malyah.fastpay.bills.domain.BillRules.Rejected;
import tech.malyah.fastpay.bills.domain.BillRules.Service;
import tech.malyah.fastpay.bills.domain.BillRules.Valid;
import tech.malyah.fastpay.establishments.ServiceBase;

/** Composição C/D/K/S (H22, PRD §10.2, FR-68, especificação §7): tabela de bases do serviço, centavos e limites. */
class BillRulesTest {

    private static BigDecimal v(String s) {
        return new BigDecimal(s);
    }

    private static Service servico(String percentual, ServiceBase base, boolean comCouvert, boolean retirado) {
        return new Service(true, v(percentual), base, comCouvert, retirado);
    }

    @Test
    void exemploDaEspecificacao() {
        Composition c = BillRules.compose(v("100.00"), new Discount(Kind.FIXO, v("10.00")), v("5.00"),
                servico("10", ServiceBase.APOS_DESCONTO, false, false));
        assertThat(c).isEqualTo(new Composition(v("100.00"), v("10.00"), v("5.00"), v("90.00"), v("9.00"),
                v("104.00")));
    }

    /** C, desconto, K, base, couvert na base → B, S, total. Percentual do serviço 10% (H14, padrão). */
    @ParameterizedTest(name = "C={0} {1} {2} K={3} {4} couvert na base={5} → B={6} S={7} T={8}")
    @CsvSource({
            // Base após desconto (padrão), sem couvert na base.
            "100.00, FIXO,       10.00,   5.00, APOS_DESCONTO,  false,  90.00,  9.00, 104.00",
            // Base antes do desconto: o serviço não acompanha o desconto.
            "100.00, FIXO,       10.00,   5.00, ANTES_DESCONTO, false, 100.00, 10.00, 105.00",
            // Couvert na base: entra só se configurado.
            "100.00, FIXO,       10.00,   5.00, APOS_DESCONTO,  true,   95.00,  9.50, 104.50",
            "100.00, FIXO,       10.00,   5.00, ANTES_DESCONTO, true,  105.00, 10.50, 105.50",
            // Percentual: D arredondado HALF_UP antes de formar a base (33,33...% de 10,00 = 3,33).
            " 10.00, PERCENTUAL, 33.3333, 0.00, APOS_DESCONTO,  false,   6.67,  0.67,   7.34",
            // Centavo HALF_UP: 12,5% de 0,20 = 0,025 → 0,03; serviço de 0,17 = 0,017 → 0,02.
            "  0.20, PERCENTUAL, 12.5,    0.00, APOS_DESCONTO,  false,   0.17,  0.02,   0.19",
            // Serviço exatamente no meio do centavo: 10% de 0,05 = 0,005 → 0,01.
            "  0.05, PERCENTUAL, 0.0001,  0.00, ANTES_DESCONTO, false,   0.05,  0.01,   0.06",
            // Desconto 100%: sobra o couvert; serviço sobre zero (ou sobre o couvert, se na base).
            " 50.00, PERCENTUAL, 100,    10.00, APOS_DESCONTO,  false,   0.00,  0.00,  10.00",
            " 50.00, FIXO,       50.00,  10.00, APOS_DESCONTO,  true,   10.00,  1.00,  11.00",
            // Antes do desconto, 100% de desconto ainda cobra o serviço sobre o consumo.
            " 50.00, FIXO,       50.00,   0.00, ANTES_DESCONTO, false,  50.00,  5.00,   5.00",
            // Desconto em reais maior que o consumo (depois de redução) vale até o consumo: total nunca negativo.
            " 30.00, FIXO,       40.00,   0.00, APOS_DESCONTO,  false,   0.00,  0.00,   0.00",
            // Sem desconto.
            " 87.35, NENHUM,     0,       0.00, APOS_DESCONTO,  false,  87.35,  8.74,  96.09"
    })
    void tabelaDeBases(String c, String tipo, String valor, String k, ServiceBase base, boolean comCouvert,
            String b, String s, String total) {
        Discount desconto = "NENHUM".equals(tipo) ? null : new Discount(Kind.valueOf(tipo), v(valor));
        Composition r = BillRules.compose(v(c), desconto, v(k), servico("10", base, comCouvert, false));
        assertThat(r.serviceBase()).isEqualByComparingTo(b);
        assertThat(r.service()).isEqualTo(v(s));
        assertThat(r.total()).isEqualTo(v(total));
        assertThat(r.total()).isEqualByComparingTo(r.consumption().subtract(r.discount()).add(r.couvert())
                .add(r.service()));
        assertThat(r.discount()).isBetween(v("0.00"), r.consumption());
    }

    @Test
    void servicoRetiradoOuDesabilitadoZeraS() {
        Discount d = new Discount(Kind.FIXO, v("10.00"));
        assertThat(BillRules.compose(v("100.00"), d, v("5.00"), servico("10", ServiceBase.APOS_DESCONTO, true, true))
                .total()).isEqualTo(v("95.00"));
        assertThat(BillRules.compose(v("100.00"), d, v("5.00"),
                new Service(false, v("0"), ServiceBase.APOS_DESCONTO, true, false)).service()).isEqualTo(v("0.00"));
    }

    @Test
    void contaVaziaEValoresGrandesSemOverflow() {
        assertThat(BillRules.compose(BigDecimal.ZERO, null, null, servico("10", ServiceBase.APOS_DESCONTO, false,
                false)).total()).isEqualTo(v("0.00"));
        // 50 linhas × 99 unidades × R$ 99.999,99, muitas vezes: BigDecimal não estoura nem perde centavo.
        BigDecimal muito = v("99999.99").multiply(v("4950")).multiply(v("1000"));
        Composition r = BillRules.compose(muito, new Discount(Kind.PERCENTUAL, v("0.0001")), v("200.00"),
                servico("20", ServiceBase.APOS_DESCONTO, true, false));
        assertThat(r.total()).isEqualByComparingTo(r.consumption().subtract(r.discount()).add(r.couvert())
                .add(r.service()));
        assertThat(r.total().scale()).isEqualTo(2);
    }

    @Test
    void limiteComparaSemDividir() {
        // 10,00 de 100,00 = 10%: no limite de 10% passa; de 9,9999% não.
        assertThat(BillRules.exceeds(v("10.00"), v("100.00"), v("10"))).isFalse();
        assertThat(BillRules.exceeds(v("10.00"), v("100.00"), v("9.9999"))).isTrue();
        // 1,00 de 3,00 = 33,333...%: o limite 33,3333% não cobre; 33,3334% cobre.
        assertThat(BillRules.exceeds(v("1.00"), v("3.00"), v("33.3333"))).isTrue();
        assertThat(BillRules.exceeds(v("1.00"), v("3.00"), v("33.3334"))).isFalse();
        assertThat(BillRules.exceeds(v("0.00"), v("0.00"), v("0"))).isFalse();
        assertThat(BillRules.effectivePercent(v("1.00"), v("3.00"))).isEqualTo(v("33.33"));
    }

    @Test
    void revisaoSoQuandoAReducaoPassaDaAutoridade() {
        Discount fixo = new Discount(Kind.FIXO, v("20.00"));
        // Concedido com 20% de 100,00; o consumo cai a 50,00: 40% efetivo, acima de 20%.
        assertThat(BillRules.needsReview(fixo, v("20.00"), v("100.00"), v("20"))).isFalse();
        assertThat(BillRules.needsReview(fixo, BillRules.discountAmount(v("50.00"), fixo), v("50.00"), v("20")))
                .isTrue();
        assertThat(BillRules.needsReview(fixo, BillRules.discountAmount(v("50.00"), fixo), v("50.00"), v("40")))
                .isFalse();
        // Percentual não muda com o consumo; o centavo do HALF_UP não vira excesso (10% de 0,15 = 0,02).
        Discount pct = new Discount(Kind.PERCENTUAL, v("10.0000"));
        assertThat(BillRules.needsReview(pct, BillRules.discountAmount(v("0.15"), pct), v("0.15"), v("10")))
                .isFalse();
        assertThat(BillRules.needsReview(pct, v("1.00"), v("10.00"), v("9.9999"))).isTrue();
        // Desconto fixo maior que o consumo vale o consumo inteiro (100%).
        assertThat(BillRules.discountAmount(v("5.00"), fixo)).isEqualTo(v("5.00"));
    }

    @ParameterizedTest
    @CsvSource({
            "FIXO, 10, 10.00", "FIXO, '10,5', 10.50", "FIXO, ' 0.01 ', 0.01", "FIXO, 9999999.99, 9999999.99",
            "PERCENTUAL, 100, 100.0000", "PERCENTUAL, 12.3456, 12.3456", "PERCENTUAL, 0.0001, 0.0001"
    })
    void formatosAceitos(String tipo, String valor, String esperado) {
        assertThat(BillRules.discount(tipo, valor)).isEqualTo(new Valid(new Discount(Kind.valueOf(tipo),
                v(esperado))));
    }

    @ParameterizedTest
    @CsvSource({
            "FIXO, 0, value", "FIXO, 1.001, value", "FIXO, -1, value", "FIXO, 1e3, value", "FIXO, 10000000, value",
            "FIXO, '', value", "PERCENTUAL, 0, value", "PERCENTUAL, 100.0001, value", "PERCENTUAL, 1.00001, value",
            "PERCENTUAL, 1000, value", "OUTRO, 10, kind", "'', 10, kind"
    })
    void formatosRecusados(String tipo, String valor, String campo) {
        assertThat(BillRules.discount(tipo, valor)).isInstanceOfSatisfying(Rejected.class,
                r -> assertThat(r.field()).isEqualTo(campo));
    }

    @Test
    void motivo() {
        assertThat(BillRules.reason("  Cliente fiel  ")).contains("Cliente fiel");
        assertThat(BillRules.reason("ok")).isEmpty();
        assertThat(BillRules.reason(null)).isEmpty();
        assertThat(BillRules.reason("x".repeat(301))).isEmpty();
    }
}
