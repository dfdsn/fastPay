package tech.malyah.fastpay.establishments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.establishments.ExitMode;
import tech.malyah.fastpay.establishments.ServiceBase;
import tech.malyah.fastpay.establishments.SettingsValues;
import tech.malyah.fastpay.establishments.domain.SettingsRules.Draft;
import tech.malyah.fastpay.establishments.domain.SettingsRules.Invalid;
import tech.malyah.fastpay.establishments.domain.SettingsRules.Valid;

/** Validação dos parâmetros da configuração (H14, FR-28–30; limites propostos em T-03). */
class SettingsRulesTest {

    static Draft padrao() {
        return new Draft(true, "10", "APOS_DESCONTO", false, false, "AUTOMATICO", null, true, true,
                "GARCOM_RESPONSAVEL", "AUTOMATICA", "America/Sao_Paulo", "06:00");
    }

    static Draft comServico(String percent, boolean enabled) {
        Draft d = padrao();
        return new Draft(enabled, percent, d.serviceBase(), d.serviceIncludesCouvert(), d.couvertEnabled(),
                d.couvertMode(), d.couvertAmount(), d.discountFixedAllowed(), d.discountPercentAllowed(),
                d.attendanceMode(), d.exitMode(), d.timeZone(), d.cutoff());
    }

    static Draft comCouvert(boolean enabled, String amount) {
        Draft d = padrao();
        return new Draft(d.serviceEnabled(), d.servicePercent(), d.serviceBase(), d.serviceIncludesCouvert(), enabled,
                "MANUAL", amount, d.discountFixedAllowed(), d.discountPercentAllowed(), d.attendanceMode(),
                d.exitMode(), d.timeZone(), d.cutoff());
    }

    static String campoRecusado(Draft draft) {
        return ((Invalid) SettingsRules.check(draft)).problems().getFirst().field();
    }

    @Test
    void padraoEValido() {
        Valid valid = (Valid) SettingsRules.check(padrao());
        SettingsValues v = valid.values();
        assertThat(v.servicePercent()).isEqualByComparingTo("10.00");
        assertThat(v.servicePercent().scale()).isEqualTo(2);
        assertThat(v.serviceBase()).isEqualTo(ServiceBase.APOS_DESCONTO);
        assertThat(v.couvertAmount()).isNull();
        assertThat(v.attendanceMode()).isEqualTo(AttendanceMode.GARCOM_RESPONSAVEL);
        assertThat(v.exitMode()).isEqualTo(ExitMode.AUTOMATICA);
        assertThat(valid.cutoff()).hasToString("06:00");
    }

    @Test
    void percentualDoServicoDeZeroAVinteComDuasCasas() {
        assertThat(((Valid) SettingsRules.check(comServico("12,5", true))).values().servicePercent())
                .isEqualByComparingTo("12.50");
        assertThat(((Valid) SettingsRules.check(comServico("20.00", true))).values().servicePercent())
                .isEqualByComparingTo("20");
        // Desabilitado pode ficar em zero; habilitado não.
        assertThat(SettingsRules.check(comServico("0", false))).isInstanceOf(Valid.class);
        assertThat(campoRecusado(comServico("0", true))).isEqualTo("servicePercent");
        assertThat(campoRecusado(comServico("20.01", true))).isEqualTo("servicePercent");
        assertThat(campoRecusado(comServico("-1", true))).isEqualTo("servicePercent");
        assertThat(campoRecusado(comServico("10.005", true))).isEqualTo("servicePercent");
        assertThat(campoRecusado(comServico("dez", true))).isEqualTo("servicePercent");
        assertThat(campoRecusado(comServico(null, true))).isEqualTo("servicePercent");
    }

    @Test
    void couvertHabilitadoExigeValorPorPessoaNoLimite() {
        assertThat(((Valid) SettingsRules.check(comCouvert(true, "R$ 15,00"))).values().couvertAmount())
                .isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(SettingsRules.check(comCouvert(true, "200"))).isInstanceOf(Valid.class);
        assertThat(campoRecusado(comCouvert(true, null))).isEqualTo("couvertAmount");
        assertThat(campoRecusado(comCouvert(true, "0"))).isEqualTo("couvertAmount");
        assertThat(campoRecusado(comCouvert(true, "200.01"))).isEqualTo("couvertAmount");
        assertThat(campoRecusado(comCouvert(true, "1.000,00"))).isEqualTo("couvertAmount");
        // Desligado, o valor anterior pode ficar guardado, mas precisa ser válido.
        assertThat(((Valid) SettingsRules.check(comCouvert(false, "12"))).values().couvertAmount())
                .isEqualByComparingTo("12");
        assertThat(campoRecusado(comCouvert(false, "abc"))).isEqualTo("couvertAmount");
    }

    @Test
    void escolhasECamposObrigatorios() {
        Draft d = padrao();
        Invalid invalid = (Invalid) SettingsRules.check(new Draft(null, d.servicePercent(), "QUALQUER", null, null,
                "SEMPRE", null, null, null, "MESA", "CATRACA", "UTC", "13:00"));
        assertThat(invalid.problems()).extracting(SettingsRules.Problem::field).containsExactly("serviceEnabled",
                "serviceBase", "serviceIncludesCouvert", "couvertEnabled", "couvertMode", "discountFixedAllowed",
                "discountPercentAllowed", "attendanceMode", "exitMode", "timeZone", "cutoff");
        assertThat(invalid.problems()).allSatisfy(p -> assertThat(p.message()).isNotBlank());
    }

    @Test
    void descontoPodeFicarSemNenhumFormato() {
        Draft d = padrao();
        Valid valid = (Valid) SettingsRules.check(new Draft(d.serviceEnabled(), d.servicePercent(), d.serviceBase(),
                d.serviceIncludesCouvert(), d.couvertEnabled(), d.couvertMode(), d.couvertAmount(), false, false,
                d.attendanceMode(), d.exitMode(), d.timeZone(), d.cutoff()));
        assertThat(valid.values().discountFixedAllowed()).isFalse();
        assertThat(valid.values().discountPercentAllowed()).isFalse();
    }

    @Test
    void auditoriaNomeiaSoOQueMudou() {
        SettingsValues antes = ((Valid) SettingsRules.check(padrao())).values();
        SettingsValues depois = ((Valid) SettingsRules.check(comCouvert(true, "10"))).values();
        assertThat(SettingsRules.changed(antes, depois)).containsExactly("couvert");
        assertThat(SettingsRules.changed(antes, ((Valid) SettingsRules.check(comServico("10.00", true))).values()))
                .isEmpty();
    }
}
