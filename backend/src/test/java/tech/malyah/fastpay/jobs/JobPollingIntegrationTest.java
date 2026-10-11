package tech.malyah.fastpay.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * O worker periódico, ligado como em produção, executa um job publicado sem chamada explícita.
 * Contexto descartado ao final para não continuar consumindo jobs de outros testes.
 */
@SpringBootTest(properties = {"fastpay.test.jobs-worker=true", "fastpay.jobs.poll-interval=200ms"})
@DirtiesContext
class JobPollingIntegrationTest extends PostgresIntegrationTest {

    static final String TIPO = "teste.periodico";

    @Autowired
    Jobs jobs;

    @Autowired
    TenantTransactions tenants;

    @Test
    void workerPeriodicoExecutaJobPublicado() throws Exception {
        UUID loja = UUID.randomUUID();
        admin().update("INSERT INTO fastpay.establishment (id, name) VALUES (?, 'Loja periódica')", loja);

        UUID jobId = tenants.inEstablishment(loja, () -> jobs.enqueue(new JobRequest(TIPO, "unico"))).jobId();

        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String status;
        do {
            Thread.sleep(100);
            status = admin().queryForObject("SELECT status FROM fastpay.job WHERE id = ?", String.class, jobId);
        } while (!"SUCCEEDED".equals(status) && System.nanoTime() < limite);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @TestConfiguration
    static class Executor {

        @Bean
        JobHandler periodico() {
            return new JobHandler() {
                @Override
                public String kind() {
                    return TIPO;
                }

                @Override
                public void handle(JobExecution execution) {
                }
            };
        }
    }
}
