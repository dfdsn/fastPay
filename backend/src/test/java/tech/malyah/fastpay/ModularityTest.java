package tech.malyah.fastpay;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Gate de fronteiras do monólito modular: falha com dependência a pacote interno de outro módulo ou ciclo.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(FastPayApplication.class);

    @Test
    void modulosRespeitamFronteiras() {
        modules.verify();
    }

    @Test
    void geraDocumentacaoDosModulos() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
