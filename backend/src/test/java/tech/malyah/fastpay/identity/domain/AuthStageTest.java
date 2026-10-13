package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.identity.MembershipRole;

class AuthStageTest {

    @Test
    void mfaObrigatorioSoParaPerfisPrivilegiados() {
        assertThat(AuthStage.mfaRequired(true, List.of())).isTrue();
        assertThat(AuthStage.mfaRequired(false, List.of(MembershipRole.ADMIN_ESTABELECIMENTO))).isTrue();
        assertThat(AuthStage.mfaRequired(false, List.of(MembershipRole.GARCOM, MembershipRole.GERENTE))).isTrue();
        assertThat(AuthStage.mfaRequired(false, List.of(MembershipRole.GARCOM, MembershipRole.PRODUCAO))).isFalse();
        assertThat(AuthStage.mfaRequired(false, List.of())).isFalse();
    }

    @Test
    void etapaDepoisDaSenha() {
        assertThat(AuthStage.afterPassword(true, false)).isEqualTo(AuthStage.MFA_PENDENTE);
        assertThat(AuthStage.afterPassword(true, true)).isEqualTo(AuthStage.MFA_PENDENTE);
        assertThat(AuthStage.afterPassword(false, true)).isEqualTo(AuthStage.MFA_CADASTRO_OBRIGATORIO);
        assertThat(AuthStage.afterPassword(false, false)).isEqualTo(AuthStage.AUTENTICADO);
    }

    @Test
    void soCadastroObrigatorioERecadastroPermitemCadastrarNaEtapaIntermediaria() {
        assertThat(AuthStage.MFA_CADASTRO_OBRIGATORIO.allowsEnrollment()).isTrue();
        assertThat(AuthStage.MFA_RECADASTRO.allowsEnrollment()).isTrue();
        assertThat(AuthStage.MFA_PENDENTE.allowsEnrollment()).isFalse();
        assertThat(AuthStage.AUTENTICADO.allowsEnrollment()).isFalse();
    }
}
