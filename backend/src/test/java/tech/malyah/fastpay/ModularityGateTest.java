package tech.malyah.fastpay;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Prova que o gate de {@link ModularityTest} detecta violações reais, usando um fixture fora do pacote da aplicação.
 */
class ModularityGateTest {

    @Test
    void acusaAcessoAPacoteInternoDeOutroModulo() {
        // O fixture fica em test-classes, que a importação padrão exclui.
        ApplicationModules fixture = ApplicationModules.of("tech.malyah.modulithfixture.violation", location -> true);

        assertThatExceptionOfType(Violations.class)
                .isThrownBy(fixture::verify)
                .withMessageContaining("billing");
    }
}
