package tech.malyah.fastpay.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.jobs.infrastructure.JdbcJobStore;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Prova da H04 com PostgreSQL real e o papel de runtime: rollback sem publicação, evento duplicado,
 * reinício entre commit e efeito, execuções concorrentes, isolamento do job por unidade, cinco
 * tentativas para não financeiros, financeiro sem teto e retomada autorizada da fila de falhas.
 *
 * <p>Backoff zero e lease de 1 s para que novas tentativas e reservas expiradas sejam imediatas.
 */
@SpringBootTest(properties = {
        "fastpay.jobs.initial-backoff=0s",
        "fastpay.jobs.max-backoff=0s",
        "fastpay.jobs.lease=1s",
        "fastpay.jobs.batch-size=100"
})
class JobsIntegrationTest extends PostgresIntegrationTest {

    static final String EFEITO = "teste.efeito";
    static final String FALHA = "teste.falha";
    static final String FINANCEIRO = "teste.financeiro";
    static final String BLOQUEIA = "teste.bloqueia";
    static final String INVADE = "teste.invade";

    @Autowired
    Jobs jobs;

    @Autowired
    JobWorker worker;

    @Autowired
    JdbcJobStore store;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Handlers handlers;

    UUID lojaA;
    UUID lojaB;

    @BeforeEach
    void preparar() {
        // Outros testes deste contexto deixam jobs pendentes; tira-os da fila para cada teste ver só os seus.
        admin().update("""
                UPDATE fastpay.job SET status = 'SUCCEEDED', lease_until = NULL, lease_token = NULL
                WHERE status IN ('PENDING', 'RUNNING')
                """);
        lojaA = UUID.randomUUID();
        lojaB = UUID.randomUUID();
        admin().update("INSERT INTO fastpay.establishment (id, name) VALUES (?, 'Loja A'), (?, 'Loja B')", lojaA, lojaB);
        handlers.reset();
    }

    @Test
    void rollbackDoFatoNaoPublicaOJob() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> {
            jobs.enqueue(new JobRequest(EFEITO, "pedido-1"));
            throw new IllegalStateException("fato de negócio falhou");
        })).hasMessage("fato de negócio falhou");

        assertThat(jobsDa(lojaA)).isZero();
        assertThat(worker.runOnce()).isZero();
        assertThat(handlers.execucoes(EFEITO)).isZero();
    }

    @Test
    void intencaoSoPodeSerGravadaNaTransacaoComContexto() {
        assertThatThrownBy(() -> jobs.enqueue(new JobRequest(EFEITO, "fora")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transação do fato");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> jobs.enqueue(new JobRequest(EFEITO, "sem-contexto"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contexto de estabelecimento");

        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA,
                () -> jobs.enqueue(new JobRequest("teste.inexistente", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commitPublicaEWorkerExecutaNoContextoDaUnidade() {
        UUID jobId = enfileirar(lojaA, EFEITO, "pedido-1", "corr-1").jobId();

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(situacao(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1)
                .containsEntry("last_error", null);
        assertThat(handlers.execucoes(EFEITO)).isEqualTo(1);
        assertThat(handlers.contextoVisto(jobId)).isEqualTo(lojaA);
        assertThat(handlers.correlacaoVista(jobId)).isEqualTo("corr-1");
        assertThat(efeitosGravados(jobId)).isEqualTo(1);
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void eventoDuplicadoNaoCriaSegundoJobNemSegundoEfeito() {
        Jobs.Enqueued primeiro = enfileirar(lojaA, EFEITO, "pedido-1", null);
        Jobs.Enqueued repetido = enfileirar(lojaA, EFEITO, "pedido-1", null);
        Jobs.Enqueued outraUnidade = enfileirar(lojaB, EFEITO, "pedido-1", null);

        assertThat(primeiro.created()).isTrue();
        assertThat(repetido.created()).isFalse();
        assertThat(repetido.jobId()).isEqualTo(primeiro.jobId());
        assertThat(outraUnidade.created()).isTrue();

        worker.runOnce();
        Jobs.Enqueued depoisDeConcluido = enfileirar(lojaA, EFEITO, "pedido-1", null);

        assertThat(depoisDeConcluido.created()).isFalse();
        assertThat(worker.runOnce()).isZero();
        assertThat(efeitosGravados(primeiro.jobId())).isEqualTo(1);
        assertThat(handlers.execucoes(EFEITO)).isEqualTo(2);
    }

    @Test
    void reinicioEntreCommitEEfeitoRetomaOJob() throws Exception {
        // Commit feito e worker "caído" logo depois de reservar, sem executar o efeito.
        UUID jobId = enfileirar(lojaA, EFEITO, "pedido-1", null).jobId();
        assertThat(store.claimDue(10, java.time.Duration.ofSeconds(1))).hasSize(1);
        assertThat(situacao(jobId)).containsEntry("status", "RUNNING");

        // Enquanto a reserva vale, ninguém executa.
        assertThat(worker.runOnce()).isZero();
        esperarLeaseExpirar(jobId);

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(situacao(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2);
        assertThat(efeitosGravados(jobId)).isEqualTo(1);
    }

    @Test
    void duasExecucoesConcorrentesExecutamCadaJobUmaVez() throws Exception {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(enfileirar(i % 2 == 0 ? lojaA : lojaB, EFEITO, "pedido-" + i, null).jobId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                largada.await();
                return worker.runOnce();
            });
            Future<Integer> b = pool.submit(() -> {
                largada.await();
                return worker.runOnce();
            });
            largada.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS)).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }

        assertThat(handlers.execucoes(EFEITO)).isEqualTo(20);
        for (UUID id : ids) {
            assertThat(efeitosGravados(id)).as("efeito do job %s", id).isEqualTo(1);
            assertThat(situacao(id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
        }
    }

    @Test
    void jobEmExecucaoNaoEhRetomadoPorOutroWorkerMesmoComLeaseVencido() throws Exception {
        UUID jobId = enfileirar(lojaA, BLOQUEIA, "lento", null).jobId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> primeiro = pool.submit(worker::runOnce);
            assertThat(handlers.entrouNoBloqueio.await(10, TimeUnit.SECONDS)).isTrue();
            esperarLeaseExpirar(jobId);

            assertThat(worker.runOnce()).as("segundo worker").isZero();

            handlers.liberarBloqueio.countDown();
            assertThat(primeiro.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            handlers.liberarBloqueio.countDown();
            pool.shutdownNow();
        }

        assertThat(handlers.execucoes(BLOQUEIA)).isEqualTo(1);
        assertThat(situacao(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1);
    }

    @Test
    void jobSoEnxergaEGravaNaPropriaUnidade() {
        admin().update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.de_b')",
                UUID.randomUUID(), lojaB);
        UUID jobA = enfileirar(lojaA, EFEITO, "pedido-1", null).jobId();
        UUID invasor = enfileirar(lojaA, INVADE, "invade-b", null).jobId();
        handlers.alvoDaInvasao = lojaB;

        worker.runOnce();

        assertThat(handlers.eventosVisiveis(jobA)).isZero();
        Map<String, Object> recusado = situacao(invasor);
        assertThat(recusado).containsEntry("status", "PENDING");
        assertThat((String) recusado.get("last_error")).matches("Falha técnica \\(\\w+\\)\\.");
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.audit_event WHERE establishment_id = ?",
                Integer.class, lojaB)).isEqualTo(1);

        // Sem contexto, o runtime não vê jobs; a reserva só devolve id, unidade e lease.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job", Integer.class)).isZero();
        assertThat(tenants.inEstablishment(lojaB, () -> jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE id IN (?, ?)", Integer.class, jobA, invasor))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.routines r
                JOIN information_schema.parameters p USING (specific_schema, specific_name)
                WHERE r.routine_name = 'claim_due_jobs' AND p.parameter_mode = 'OUT'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void naoFinanceiroEsgotaEmCincoTentativasComErroSanitizado() {
        UUID jobId = enfileirar(lojaA, FALHA, "email-1", null).jobId();

        for (int i = 1; i <= 4; i++) {
            assertThat(worker.runOnce()).isEqualTo(1);
            assertThat(situacao(jobId)).containsEntry("status", "PENDING").containsEntry("attempts", i);
        }
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(worker.runOnce()).isZero();

        Map<String, Object> esgotado = situacao(jobId);
        assertThat(esgotado).containsEntry("status", "EXHAUSTED").containsEntry("attempts", 5)
                .containsEntry("max_attempts", 5);
        assertThat((String) esgotado.get("last_error")).isEqualTo("Falha técnica (IllegalStateException).")
                .doesNotContain("4111");
        assertThat(handlers.execucoes(FALHA)).isEqualTo(5);
        assertThat(efeitosGravados(jobId)).as("efeito desfeito em cada falha").isZero();
        assertThat(jobs.exhausted(lojaA)).extracting(JobSummary::jobId).containsExactly(jobId);
        assertThat(jobs.exhausted(lojaB)).isEmpty();
    }

    @Test
    void jobInterrompidoNaUltimaTentativaVaiParaFilaDeFalhas() throws Exception {
        UUID jobId = enfileirar(lojaA, FALHA, "email-1", null).jobId();
        for (int i = 0; i < 4; i++) {
            worker.runOnce();
        }
        store.claimDue(10, java.time.Duration.ofSeconds(1));
        esperarLeaseExpirar(jobId);

        assertThat(worker.runOnce()).isZero();

        assertThat(situacao(jobId)).containsEntry("status", "EXHAUSTED").containsEntry("attempts", 5)
                .containsEntry("last_error", "Execução interrompida antes de concluir (lease expirado).");
    }

    @Test
    void financeiroIncertoContinuaAlemDeCincoTentativas() {
        UUID jobId = enfileirar(lojaA, FINANCEIRO, "pagamento-1", null).jobId();

        for (int i = 0; i < 7; i++) {
            assertThat(worker.runOnce()).isEqualTo(1);
        }

        assertThat(situacao(jobId)).containsEntry("status", "PENDING").containsEntry("attempts", 7)
                .containsEntry("max_attempts", null)
                .containsEntry("last_error", "Pagamento ainda sem situação definitiva no provedor.");
    }

    @Test
    void retomadaAutorizadaReexecutaAMesmaOperacaoComAuditoria() {
        UUID jobId = enfileirar(lojaA, FALHA, "email-1", "corr-falha").jobId();
        for (int i = 0; i < 5; i++) {
            worker.runOnce();
        }
        UUID gerente = UUID.randomUUID();

        assertThat(jobs.retryExhausted(lojaB, jobId, gerente, "corr-retomada")).as("outra unidade").isFalse();
        handlers.falhaResolvida.set(true);
        assertThat(jobs.retryExhausted(lojaA, jobId, gerente, "corr-retomada")).isTrue();
        assertThat(jobs.retryExhausted(lojaA, jobId, gerente, "corr-retomada")).as("já retomado").isFalse();
        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(situacao(jobId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 6)
                .containsEntry("max_attempts", 10);
        assertThat(efeitosGravados(jobId)).isEqualTo(1);
        assertThat(admin().queryForMap("""
                SELECT action, actor_id, correlation_id FROM fastpay.audit_event
                WHERE establishment_id = ? AND subject_id = ? AND action LIKE 'job.%'
                """, lojaA, jobId)).containsEntry("action", "job.retomada_autorizada")
                .containsEntry("actor_id", gerente).containsEntry("correlation_id", "corr-retomada");
    }

    private Jobs.Enqueued enfileirar(UUID loja, String tipo, String chave, String correlacao) {
        return tenants.inEstablishment(loja,
                () -> jobs.enqueue(new JobRequest(tipo, chave, "{\"origem\":\"" + chave + "\"}", correlacao)));
    }

    private Map<String, Object> situacao(UUID jobId) {
        return admin().queryForMap(
                "SELECT status, attempts, max_attempts, last_error FROM fastpay.job WHERE id = ?", jobId);
    }

    private Integer jobsDa(UUID loja) {
        return admin().queryForObject("SELECT count(*) FROM fastpay.job WHERE establishment_id = ?", Integer.class, loja);
    }

    private Integer efeitosGravados(UUID jobId) {
        return admin().queryForObject("SELECT count(*) FROM fastpay.audit_event WHERE action = 'teste.efeito' AND subject_id = ?",
                Integer.class, jobId);
    }

    private void esperarLeaseExpirar(UUID jobId) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Boolean.TRUE.equals(admin().queryForObject(
                "SELECT lease_until <= now() FROM fastpay.job WHERE id = ?", Boolean.class, jobId))) {
            assertThat(System.nanoTime()).as("lease não expirou").isLessThan(limite);
            Thread.sleep(100);
        }
    }

    /**
     * Executores de teste. O efeito de banco é uma linha em {@code audit_event} com o ID do job, gravada
     * pelo papel de runtime na transação do job.
     */
    @TestConfiguration
    static class Handlers {

        final Map<String, AtomicInteger> execucoes = new ConcurrentHashMap<>();
        final Map<UUID, UUID> contextos = new ConcurrentHashMap<>();
        final Map<UUID, String> correlacoes = new ConcurrentHashMap<>();
        final Map<UUID, Integer> visiveis = new ConcurrentHashMap<>();
        final AtomicBoolean falhaResolvida = new AtomicBoolean();
        volatile CountDownLatch entrouNoBloqueio = new CountDownLatch(1);
        volatile CountDownLatch liberarBloqueio = new CountDownLatch(1);
        volatile UUID alvoDaInvasao;

        void reset() {
            execucoes.clear();
            contextos.clear();
            correlacoes.clear();
            visiveis.clear();
            falhaResolvida.set(false);
            entrouNoBloqueio = new CountDownLatch(1);
            liberarBloqueio = new CountDownLatch(1);
            alvoDaInvasao = null;
        }

        int execucoes(String tipo) {
            return execucoes.getOrDefault(tipo, new AtomicInteger()).get();
        }

        UUID contextoVisto(UUID jobId) {
            return contextos.get(jobId);
        }

        String correlacaoVista(UUID jobId) {
            return correlacoes.get(jobId);
        }

        Integer eventosVisiveis(UUID jobId) {
            return visiveis.get(jobId);
        }

        private void contar(JobExecution execucao) {
            execucoes.computeIfAbsent(execucao.kind(), k -> new AtomicInteger()).incrementAndGet();
        }

        private static void gravarEfeito(JdbcTemplate jdbc, JobExecution execucao) {
            jdbc.update("""
                    INSERT INTO audit_event (id, establishment_id, action, subject_id)
                    VALUES (?, current_establishment_id(), 'teste.efeito', ?)
                    """, UUID.randomUUID(), execucao.jobId());
        }

        @Bean
        JobHandler efeito(JdbcTemplate jdbc) {
            return handler(EFEITO, false, execucao -> {
                contextos.put(execucao.jobId(), jdbc.queryForObject("SELECT current_establishment_id()", UUID.class));
                correlacoes.put(execucao.jobId(), String.valueOf(org.slf4j.MDC.get("correlationId")));
                visiveis.put(execucao.jobId(), jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'teste.de_b'", Integer.class));
                gravarEfeito(jdbc, execucao);
            });
        }

        @Bean
        JobHandler falha(JdbcTemplate jdbc) {
            return handler(FALHA, false, execucao -> {
                gravarEfeito(jdbc, execucao);
                if (!falhaResolvida.get()) {
                    throw new IllegalStateException("SMTP recusou cartão 4111111111111111");
                }
            });
        }

        @Bean
        JobHandler financeiro() {
            return handler(FINANCEIRO, true, execucao -> {
                throw new JobFailure("Pagamento ainda sem situação definitiva no provedor.");
            });
        }

        @Bean
        JobHandler bloqueia() {
            return handler(BLOQUEIA, false, execucao -> {
                entrouNoBloqueio.countDown();
                try {
                    if (!liberarBloqueio.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("bloqueio não liberado");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
        }

        @Bean
        JobHandler invade(JdbcTemplate jdbc) {
            return handler(INVADE, false, execucao -> jdbc.update(
                    "INSERT INTO audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.invasao')",
                    UUID.randomUUID(), alvoDaInvasao));
        }

        private JobHandler handler(String tipo, boolean financeiro, java.util.function.Consumer<JobExecution> corpo) {
            return new JobHandler() {
                @Override
                public String kind() {
                    return tipo;
                }

                @Override
                public boolean financial() {
                    return financeiro;
                }

                @Override
                public void handle(JobExecution execution) {
                    contar(execution);
                    corpo.accept(execution);
                }
            };
        }
    }
}
