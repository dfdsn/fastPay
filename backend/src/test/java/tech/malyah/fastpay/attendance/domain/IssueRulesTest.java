package tech.malyah.fastpay.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.attendance.domain.IssueRules.Attend;
import tech.malyah.fastpay.attendance.domain.IssueRules.Conclude;
import tech.malyah.fastpay.attendance.domain.IssueRules.Forward;
import tech.malyah.fastpay.attendance.domain.IssueRules.Issue;
import tech.malyah.fastpay.attendance.domain.IssueRules.Refusal;
import tech.malyah.fastpay.attendance.domain.IssueRules.Refused;
import tech.malyah.fastpay.attendance.domain.IssueRules.Status;
import tech.malyah.fastpay.attendance.domain.IssueRules.Unchanged;
import tech.malyah.fastpay.establishments.AttendanceMode;

class IssueRulesTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BIA = UUID.randomUUID();

    private static Issue aberto(AttendanceMode mode) {
        return new Issue(mode, Status.ABERTO, null, false, false, 0);
    }

    private static Issue atendido(AttendanceMode mode, UUID por) {
        return new Issue(mode, Status.EM_ATENDIMENTO, por, false, false, 1);
    }

    @Test
    void centralEhExclusivaEResponsavelAceitaAjuda() {
        assertThat(IssueRules.attend(aberto(AttendanceMode.CENTRAL), ANA)).isEqualTo(new Attend(ANA));
        assertThat(IssueRules.attend(atendido(AttendanceMode.CENTRAL, ANA), ANA)).isEqualTo(new Unchanged());
        assertThat(IssueRules.attend(atendido(AttendanceMode.CENTRAL, ANA), BIA))
                .isEqualTo(new Refused(Refusal.JA_ASSUMIDO));
        assertThat(IssueRules.attend(aberto(AttendanceMode.GARCOM_RESPONSAVEL), BIA)).isEqualTo(new Attend(BIA));
        // Quem chega depois ajuda; o início continua de quem começou.
        assertThat(IssueRules.attend(atendido(AttendanceMode.GARCOM_RESPONSAVEL, ANA), BIA))
                .isEqualTo(new Unchanged());
    }

    @Test
    void reatribuirSoNaCentralComAVersaoLida() {
        assertThat(IssueRules.reassign(atendido(AttendanceMode.CENTRAL, ANA), 1, BIA)).isEqualTo(new Attend(BIA));
        assertThat(IssueRules.reassign(atendido(AttendanceMode.CENTRAL, ANA), 0, BIA))
                .isEqualTo(new Refused(Refusal.VERSAO_DESATUALIZADA));
        assertThat(IssueRules.reassign(atendido(AttendanceMode.CENTRAL, ANA), 1, ANA)).isEqualTo(new Unchanged());
        assertThat(IssueRules.reassign(aberto(AttendanceMode.GARCOM_RESPONSAVEL), 0, BIA))
                .isEqualTo(new Refused(Refusal.COM_RESPONSAVEL));
    }

    @Test
    void encaminharEConcluirSoDepoisDeAtender() {
        assertThat(IssueRules.forward(aberto(AttendanceMode.CENTRAL), 0)).isEqualTo(new Refused(Refusal.NAO_ATENDIDO));
        assertThat(IssueRules.conclude(aberto(AttendanceMode.CENTRAL), 0, true))
                .isEqualTo(new Refused(Refusal.NAO_ATENDIDO));
        assertThat(IssueRules.forward(atendido(AttendanceMode.CENTRAL, ANA), 1)).isEqualTo(new Forward());
        assertThat(IssueRules.forward(new Issue(AttendanceMode.CENTRAL, Status.EM_ATENDIMENTO, ANA, true, false, 2), 2))
                .isEqualTo(new Unchanged());
        assertThat(IssueRules.conclude(atendido(AttendanceMode.CENTRAL, ANA), 1, false)).isEqualTo(new Conclude());
        assertThat(IssueRules.conclude(atendido(AttendanceMode.CENTRAL, ANA), 0, false))
                .isEqualTo(new Refused(Refusal.VERSAO_DESATUALIZADA));
    }

    @Test
    void depoisDoEncerramentoSoQuemAutorizaDevolucaoConclui() {
        Issue pago = new Issue(AttendanceMode.GARCOM_RESPONSAVEL, Status.EM_ATENDIMENTO, ANA, true, true, 1);
        assertThat(IssueRules.conclude(pago, 1, false)).isEqualTo(new Refused(Refusal.EXIGE_GERENTE));
        assertThat(IssueRules.conclude(pago, 1, true)).isEqualTo(new Conclude());
    }

    @Test
    void tratadoNaoMudaMais() {
        Issue tratado = new Issue(AttendanceMode.CENTRAL, Status.TRATADO, ANA, false, false, 3);
        assertThat(IssueRules.attend(tratado, BIA)).isEqualTo(new Refused(Refusal.TRATADO));
        assertThat(IssueRules.reassign(tratado, 3, BIA)).isEqualTo(new Refused(Refusal.TRATADO));
        assertThat(IssueRules.forward(tratado, 3)).isEqualTo(new Refused(Refusal.TRATADO));
        assertThat(IssueRules.conclude(tratado, 3, true)).isEqualTo(new Refused(Refusal.TRATADO));
    }

    @Test
    void textoTemDeCincoATrezentosCaracteres() {
        assertThat(IssueRules.text(null)).isEmpty();
        assertThat(IssueRules.text("  ruim ")).isEmpty();
        assertThat(IssueRules.text("  veio frio  ")).contains("veio frio");
        assertThat(IssueRules.text("a".repeat(300))).isPresent();
        assertThat(IssueRules.text("a".repeat(301))).isEmpty();
    }
}
