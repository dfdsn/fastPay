package tech.malyah.fastpay.jobs.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.jobs.domain.RetryPolicy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
class JobsConfiguration {

    @Bean
    RetryPolicy jobRetryPolicy(JobProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.initialBackoff(), properties.backoffMultiplier(),
                properties.maxBackoff());
    }

    /** Busca periódica de jobs vencidos. Desligável por {@code fastpay.jobs.worker-enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "fastpay.jobs.worker-enabled", matchIfMissing = true)
    static class JobPolling {

        private final JobWorker worker;

        JobPolling(JobWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${fastpay.jobs.poll-interval:5s}", initialDelayString = "${fastpay.jobs.poll-interval:5s}")
        void poll() {
            worker.runOnce();
        }
    }
}
