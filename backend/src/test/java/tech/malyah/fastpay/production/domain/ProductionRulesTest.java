package tech.malyah.fastpay.production.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.production.domain.ProductionRules.Action;
import tech.malyah.fastpay.production.domain.ProductionRules.Line;
import tech.malyah.fastpay.production.domain.ProductionRules.Next;
import tech.malyah.fastpay.production.domain.ProductionRules.Refusal;
import tech.malyah.fastpay.production.domain.ProductionRules.Refused;
import tech.malyah.fastpay.production.domain.ProductionRules.Status;

class ProductionRulesTest {

    @Test
    void sequenciaCompletaSoNaOrdemESemPularEtapa() {
        assertThat(ProductionRules.initial(true)).isEqualTo(Status.AGUARDANDO_PREPARO);
        assertThat(ProductionRules.initial(false)).isEqualTo(Status.PRONTO);

        assertThat(decide(Status.AGUARDANDO_PREPARO, Action.INICIADO)).isEqualTo(new Next(Status.EM_PREPARO, false));
        assertThat(decide(Status.EM_PREPARO, Action.PRONTO)).isEqualTo(new Next(Status.PRONTO, false));
        assertThat(decide(Status.PRONTO, Action.ENTREGUE)).isEqualTo(new Next(Status.ENTREGUE, false));

        assertThat(decide(Status.AGUARDANDO_PREPARO, Action.PRONTO)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.AGUARDANDO_PREPARO, Action.ENTREGUE)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.EM_PREPARO, Action.INICIADO)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.EM_PREPARO, Action.ENTREGUE)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.PRONTO, Action.PRONTO)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.ENTREGUE, Action.ENTREGUE)).isEqualTo(etapaInvalida());
    }

    @Test
    void impossivelSoAntesDoProntoComSetorEBloqueiaTodoAvanco() {
        assertThat(decide(Status.AGUARDANDO_PREPARO, Action.IMPOSSIVEL))
                .isEqualTo(new Next(Status.AGUARDANDO_PREPARO, true));
        assertThat(decide(Status.EM_PREPARO, Action.IMPOSSIVEL)).isEqualTo(new Next(Status.EM_PREPARO, true));
        assertThat(decide(Status.PRONTO, Action.IMPOSSIVEL)).isEqualTo(etapaInvalida());
        assertThat(decide(Status.ENTREGUE, Action.IMPOSSIVEL)).isEqualTo(etapaInvalida());
        assertThat(ProductionRules.decide(new Line(Status.PRONTO, false, false), Action.IMPOSSIVEL))
                .isEqualTo(etapaInvalida());

        Refused bloqueada = new Refused(Refusal.LINHA_IMPOSSIVEL);
        for (Action avanco : new Action[] {Action.INICIADO, Action.PRONTO, Action.ENTREGUE}) {
            assertThat(ProductionRules.decide(new Line(Status.AGUARDANDO_PREPARO, true, true), avanco))
                    .isEqualTo(bloqueada);
            assertThat(ProductionRules.decide(new Line(Status.EM_PREPARO, true, true), avanco)).isEqualTo(bloqueada);
        }
        assertThat(ProductionRules.decide(new Line(Status.EM_PREPARO, true, true), Action.IMPOSSIVEL))
                .isEqualTo(etapaInvalida());
        assertThat(ProductionRules.decide(new Line(Status.EM_PREPARO, true, true), Action.IMPOSSIVEL_RETIRADO))
                .isEqualTo(new Next(Status.EM_PREPARO, false));
        assertThat(decide(Status.EM_PREPARO, Action.IMPOSSIVEL_RETIRADO)).isEqualTo(etapaInvalida());
    }

    @Test
    void motivoNormalizadoNosLimitesExatos() {
        assertThat(ProductionRules.reason(null)).isNull();
        assertThat(ProductionRules.reason("  ab  c ")).isNull();
        assertThat(ProductionRules.reason("  Acabou \n o   gelo ")).isEqualTo("Acabou o gelo");
        assertThat(ProductionRules.reason("x".repeat(5))).hasSize(5);
        assertThat(ProductionRules.reason("x".repeat(300))).hasSize(300);
        assertThat(ProductionRules.reason("x".repeat(301))).isNull();
    }

    @Test
    void atrasoDepoisDoLimiteDoSetorAteAEntrega() {
        Instant envio = Instant.parse("2026-11-04T20:00:00Z");
        Instant limite = envio.plusSeconds(15 * 60);
        assertThat(ProductionRules.late(Status.EM_PREPARO, envio, 15, limite)).isFalse();
        assertThat(ProductionRules.late(Status.EM_PREPARO, envio, 15, limite.plusSeconds(1))).isTrue();
        assertThat(ProductionRules.late(Status.PRONTO, envio, 15, limite.plusSeconds(1))).isTrue();
        assertThat(ProductionRules.late(Status.ENTREGUE, envio, 15, limite.plusSeconds(1))).isFalse();
        assertThat(ProductionRules.late(Status.PRONTO, envio, null, limite.plusSeconds(3600))).isFalse();
    }

    private static ProductionRules.Decision decide(Status status, Action action) {
        return ProductionRules.decide(new Line(status, true, false), action);
    }

    private static Refused etapaInvalida() {
        return new Refused(Refusal.ETAPA_INVALIDA);
    }
}
