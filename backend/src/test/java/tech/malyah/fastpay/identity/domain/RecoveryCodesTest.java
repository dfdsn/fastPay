package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void dezCodigosDiferentesEmQuatroGruposSemCaracteresAmbiguos() {
        List<String> codigos = RecoveryCodes.generate();

        assertThat(codigos).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}"))
                .noneMatch(c -> c.chars().anyMatch(ch -> "01ilou".indexOf(ch) >= 0));
        assertThat(RecoveryCodes.generate()).doesNotContainAnyElementsOf(codigos);
    }

    @Test
    void normalizaMaiusculasEspacosEHifens() {
        assertThat(RecoveryCodes.normalize(" ABCD efgh-JKMN pqrs ")).hasValue("abcdefghjkmnpqrs");
        assertThat(RecoveryCodes.normalize("abcd-efgh-jkmn")).isEmpty();
        assertThat(RecoveryCodes.normalize("abcd-efgh-jkmn-pqr0")).isEmpty();
        assertThat(RecoveryCodes.normalize("123456")).isEmpty();
        assertThat(RecoveryCodes.normalize(null)).isEmpty();
    }

    @Test
    void hashDependeDaIdentidade() {
        String codigo = "abcdefghjkmnpqrs";
        UUID ana = UUID.randomUUID();

        assertThat(RecoveryCodes.hash(ana, codigo)).matches("[0-9a-f]{64}").doesNotContain(codigo)
                .isEqualTo(RecoveryCodes.hash(ana, codigo))
                .isNotEqualTo(RecoveryCodes.hash(UUID.randomUUID(), codigo));
    }
}
