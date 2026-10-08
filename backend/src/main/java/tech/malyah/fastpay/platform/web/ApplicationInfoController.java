package tech.malyah.fastpay.platform.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identificação pública e não sensível da aplicação, usada pela tela inicial.
 */
@RestController
@RequestMapping("/api/v1/platform")
class ApplicationInfoController {

    private final String version;
    private final String environment;

    ApplicationInfoController(ObjectProvider<BuildProperties> buildProperties,
                              @Value("${fastpay.environment}") String environment) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "desconhecida";
        this.environment = environment;
    }

    @GetMapping("/info")
    ApplicationInfo info() {
        return new ApplicationInfo("fastPay", version, environment);
    }

    record ApplicationInfo(String name, String version, String environment) {
    }
}
