package tech.malyah.fastpay.tabs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.tabs.domain.TabRules.AccountCheck;
import tech.malyah.fastpay.tabs.domain.TabRules.Recovery;
import tech.malyah.fastpay.tabs.domain.TabRules.Rejected;
import tech.malyah.fastpay.tabs.domain.TabRules.Valid;

class TabRulesTest {

    @Test
    void nomeENormalizadoEObrigatorio() {
        assertThat(TabRules.name("  Ana \t Paula ")).isEqualTo(new Valid<>("Ana Paula"));
        assertThat(TabRules.name("a".repeat(60))).isInstanceOf(Valid.class);
        assertThat(field(TabRules.name("a".repeat(61)))).isEqualTo("name");
        assertThat(field(TabRules.name("   "))).isEqualTo("name");
        assertThat(field(TabRules.name(null))).isEqualTo("name");
    }

    @Test
    void mesaEOpcional() {
        assertThat(TabRules.table(null)).isEqualTo(new Valid<String>(null));
        assertThat(TabRules.table("  ")).isEqualTo(new Valid<String>(null));
        assertThat(TabRules.table(" Varanda 2 ")).isEqualTo(new Valid<>("Varanda 2"));
        assertThat(field(TabRules.table("1".repeat(21)))).isEqualTo("table");
    }

    @Test
    void recuperacaoPedeNomeItemOuHoraEMotivo() {
        assertThat(TabRules.recovery(true, "ITEM_DA_CONTA", " Celular  sem bateria "))
                .isEqualTo(new Valid<>(new Recovery(AccountCheck.ITEM_DA_CONTA, "Celular sem bateria")));
        assertThat(TabRules.recovery(true, "HORA_DA_ABERTURA", "Sem pedido ainda")).isInstanceOf(Valid.class);
        assertThat(field(TabRules.recovery(null, "ITEM_DA_CONTA", "motivo"))).isEqualTo("nameChecked");
        assertThat(field(TabRules.recovery(false, "ITEM_DA_CONTA", "motivo"))).isEqualTo("nameChecked");
        assertThat(field(TabRules.recovery(true, null, "motivo"))).isEqualTo("accountCheck");
        assertThat(field(TabRules.recovery(true, "NOME", "motivo"))).isEqualTo("accountCheck");
        assertThat(field(TabRules.recovery(true, "ITEM_DA_CONTA", "abcd"))).isEqualTo("reason");
        assertThat(TabRules.recovery(true, "ITEM_DA_CONTA", "abcde")).isInstanceOf(Valid.class);
        assertThat(field(TabRules.recovery(true, "ITEM_DA_CONTA", "a".repeat(301)))).isEqualTo("reason");
    }

    @Test
    void soUnidadeAtivaAbreEmProducao() {
        assertThat(TabRules.unitMayOpenTabs(true, false)).isFalse();
        assertThat(TabRules.unitMayOpenTabs(true, true)).isTrue();
        assertThat(TabRules.unitMayOpenTabs(false, false)).isTrue();
        assertThat(TabRules.unitMayOpenTabs(false, true)).isTrue();
    }

    private static String field(TabRules.Checked<?> checked) {
        return ((Rejected<?>) checked).invalid().field();
    }
}
