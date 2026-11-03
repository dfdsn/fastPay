package tech.malyah.fastpay.tabs.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TabsProperties.class)
class TabsConfiguration {
}
