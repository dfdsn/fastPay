package tech.malyah.fastpay.sync.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import tech.malyah.fastpay.sync.Streams;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
class SyncConfiguration {

    /** Envio periódico às conexões abertas. Desligável por {@code fastpay.sync.dispatcher-enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "fastpay.sync.dispatcher-enabled", matchIfMissing = true)
    static class Dispatching {

        private final Streams streams;

        Dispatching(Streams streams) {
            this.streams = streams;
        }

        @Scheduled(fixedDelayString = "${fastpay.sync.dispatch-interval:1s}")
        void dispatch() {
            streams.dispatch();
        }
    }
}
