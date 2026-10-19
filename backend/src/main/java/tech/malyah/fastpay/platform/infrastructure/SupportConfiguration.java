package tech.malyah.fastpay.platform.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportProperties.class)
class SupportConfiguration {
}
