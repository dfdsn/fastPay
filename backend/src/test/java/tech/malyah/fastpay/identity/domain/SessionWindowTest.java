package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.identity.MembershipRole;

/** Prazos de sessão por perfil (especificação §5) e escolha do perfil mais restritivo. */
class SessionWindowTest {

    Instant login = Instant.parse("2026-10-12T18:00:00Z");

    @Test
    void perfilMaisRestritivoAlcancadoPelaSessao() {
        assertThat(SessionProfile.mostRestrictive(false, List.of(MembershipRole.GARCOM, MembershipRole.PRODUCAO)))
                .isEqualTo(SessionProfile.OPERACAO);
        assertThat(SessionProfile.mostRestrictive(false, List.of())).isEqualTo(SessionProfile.OPERACAO);
        assertThat(SessionProfile.mostRestrictive(false, List.of(MembershipRole.GARCOM, MembershipRole.GERENTE)))
                .isEqualTo(SessionProfile.GESTAO);
        assertThat(SessionProfile.mostRestrictive(false, List.of(MembershipRole.ADMIN_ESTABELECIMENTO)))
                .isEqualTo(SessionProfile.GESTAO);
        assertThat(SessionProfile.mostRestrictive(true, List.of(MembershipRole.GARCOM))).isEqualTo(SessionProfile.PLATAFORMA);
    }

    @Test
    void prazosDaEspecificacao() {
        assertThat(SessionProfile.OPERACAO.idleTimeout()).isEqualTo(Duration.ofHours(1));
        assertThat(SessionProfile.OPERACAO.absoluteTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(SessionProfile.GESTAO.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(SessionProfile.GESTAO.absoluteTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(SessionProfile.PLATAFORMA.idleTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(SessionProfile.PLATAFORMA.absoluteTimeout()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void venceNoInstanteDoPrazoDeInatividade() {
        SessionWindow janela = SessionWindow.startingAt(SessionProfile.GESTAO, login);

        assertThat(janela.expiredAt(login.plus(Duration.ofMinutes(30)).minusMillis(1))).isFalse();
        assertThat(janela.expiredAt(login.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void atividadeAdiaInatividadeMasNaoOLimiteAbsoluto() {
        SessionWindow janela = SessionWindow.startingAt(SessionProfile.PLATAFORMA, login);
        Instant quase = login.plus(Duration.ofHours(8)).minusSeconds(60);

        SessionWindow ativa = janela.touchedAt(quase);

        assertThat(ativa.idleDeadline()).isEqualTo(quase.plus(Duration.ofMinutes(15)));
        assertThat(ativa.absoluteDeadline()).isEqualTo(login.plus(Duration.ofHours(8)));
        assertThat(ativa.expiredAt(quase.plusSeconds(30))).isFalse();
        assertThat(ativa.expiredAt(login.plus(Duration.ofHours(8)))).isTrue();
    }

    @Test
    void relogioQueVoltaNaoRecuaAUltimaAtividade() {
        SessionWindow janela = SessionWindow.startingAt(SessionProfile.OPERACAO, login);

        assertThat(janela.touchedAt(login.minusSeconds(10))).isSameAs(janela);
    }

    @Test
    void exigeTodosOsCampos() {
        assertThatThrownBy(() -> new SessionWindow(null, login, login)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionWindow(SessionProfile.GESTAO, null, login)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SessionWindow(SessionProfile.GESTAO, login, null)).isInstanceOf(NullPointerException.class);
    }
}
