package tech.malyah.fastpay.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.attendance.domain.CallRules.Assign;
import tech.malyah.fastpay.attendance.domain.CallRules.Call;
import tech.malyah.fastpay.attendance.domain.CallRules.Refusal;
import tech.malyah.fastpay.attendance.domain.CallRules.Refused;
import tech.malyah.fastpay.attendance.domain.CallRules.Unchanged;
import tech.malyah.fastpay.establishments.AttendanceMode;

/** Quem responde pelo chamado (H20, FR-23). */
class CallRulesTest {

    private final UUID ana = UUID.randomUUID();
    private final UUID bia = UUID.randomUUID();

    @Test
    void naCentralOPrimeiroAssumeDeFormaExclusiva() {
        Call livre = new Call(AttendanceMode.CENTRAL, null, false, 0);
        assertThat(CallRules.claim(livre, ana)).isEqualTo(new Assign(ana));
        Call daAna = new Call(AttendanceMode.CENTRAL, ana, false, 1);
        assertThat(CallRules.claim(daAna, ana)).isInstanceOf(Unchanged.class);
        assertThat(CallRules.claim(daAna, bia)).isEqualTo(new Refused(Refusal.JA_ASSUMIDO));
    }

    @Test
    void garcomResponsavelNaoTemClaimNemReatribuicaoPorChamado() {
        Call call = new Call(AttendanceMode.GARCOM_RESPONSAVEL, null, false, 0);
        assertThat(CallRules.claim(call, ana)).isEqualTo(new Refused(Refusal.COM_RESPONSAVEL));
        assertThat(CallRules.reassign(call, 0, ana)).isEqualTo(new Refused(Refusal.COM_RESPONSAVEL));
    }

    @Test
    void concluidoNaoMudaMais() {
        Call call = new Call(AttendanceMode.CENTRAL, ana, true, 2);
        assertThat(CallRules.claim(call, bia)).isEqualTo(new Refused(Refusal.CONCLUIDO));
        assertThat(CallRules.reassign(call, 2, bia)).isEqualTo(new Refused(Refusal.CONCLUIDO));
    }

    @Test
    void reatribuicaoExigeAVersaoLida() {
        Call call = new Call(AttendanceMode.CENTRAL, ana, false, 1);
        assertThat(CallRules.reassign(call, 0, bia)).isEqualTo(new Refused(Refusal.VERSAO_DESATUALIZADA));
        assertThat(CallRules.reassign(call, 1, bia)).isEqualTo(new Assign(bia));
        assertThat(CallRules.reassign(call, 1, ana)).isInstanceOf(Unchanged.class);
        assertThat(CallRules.reassign(new Call(AttendanceMode.CENTRAL, null, false, 0), 0, bia))
                .isEqualTo(new Assign(bia));
    }
}
