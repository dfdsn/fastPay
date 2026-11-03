package tech.malyah.fastpay.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Envio de pedido pela API (H16, FR-14–FR-16, CA-05–CA-07), com PostgreSQL real, RLS, Spring Session e CSRF. Cobre a
 * validação exigida no backlog: envio repetido (mesma chave, simultâneo e depois de timeout), fechamento concorrente
 * pelo contrato da trava da comanda, preço mudado e item esgotado, restart entre a gravação e a produção (job
 * pendente) e os limites exatos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class OrdersIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID garcom;
    UUID outroGarcom;
    UUID producao;
    UUID garcomDaOutra;
    UUID cozinha;
    UUID batata;
    UUID chopp;
    UUID agua;
    UUID produtoDaOutra;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Pedido");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        garcom = fixtures.identity("Gabi Garçom");
        outroGarcom = fixtures.identity("Gui Garçom");
        producao = fixtures.identity("Paulo Produção");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(outroGarcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
        cozinha = setor(loja, "Cozinha");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
        agua = produto(loja, "Água", "5.00", null);
        produtoDaOutra = produto(outraLoja, "Pastel", "12.00", null);
    }

    @Test
    void enviaComPrecoCapturadoAlocacaoEEncaminhaAProducaoDepoisDoRestart() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");
        UUID chave = UUID.randomUUID();

        gabi.get("/api/v1/orders/operations/" + chave).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NAO_ENCONTRADO"));
        String pedido = JsonPath.read(gabi.post("/api/v1/orders", envio(comanda, chave,
                linha(batata, 2, "sem sal", "35.90"), linha(chopp, 3, null, "9.9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.order.tabId").value(comanda))
                .andExpect(jsonPath("$.order.lines.length()").value(2))
                .andExpect(jsonPath("$.order.lines[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.order.lines[0].unitPrice").value("35.90"))
                .andExpect(jsonPath("$.order.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.order.lines[0].lineTotal").value("71.80"))
                .andExpect(jsonPath("$.order.lines[0].note").value("sem sal"))
                .andExpect(jsonPath("$.order.lines[0].sectorId").value(cozinha.toString()))
                .andExpect(jsonPath("$.order.lines[1].unitPrice").value("9.90"))
                .andExpect(jsonPath("$.order.lines[1].note").value(nullValue()))
                .andExpect(jsonPath("$.order.lines[1].sectorId").value(nullValue()))
                .andExpect(jsonPath("$.order.forwarding").value("PENDENTE"))
                .andReturn().getResponse().getContentAsString(), "$.order.id");
        UUID pedidoId = UUID.fromString(pedido);

        // Cada linha inteira alocada na comanda do pedido; auditoria do envio.
        assertThat(admin().queryForList("""
                SELECT a.quantity FROM fastpay.tab_allocation a JOIN fastpay.order_line l ON l.id = a.order_line_id
                 WHERE l.order_id = ? AND a.tab_id = ? ORDER BY l.position
                """, Integer.class, pedidoId, UUID.fromString(comanda))).containsExactly(2, 3);
        assertThat(admin().queryForObject("""
                SELECT detail FROM fastpay.audit_event WHERE subject_id = ? AND action = 'pedido.enviado'
                """, String.class, pedidoId)).endsWith("2 linhas; 5 unidades");

        // "Restart" entre a gravação e a produção: o pedido está gravado, o job está pendente e nada chegou à
        // produção. O worker que sobe depois encaminha; rodar de novo não duplica.
        assertThat(producaoDo(pedidoId)).isEmpty();
        assertThat(admin().queryForObject("SELECT status FROM fastpay.job WHERE kind = ? AND dedup_key = ?",
                String.class, Orders.FORWARDING_JOB, pedido)).isEqualTo("PENDING");
        rodarJobs();
        assertThat(producaoDo(pedidoId)).containsExactly("AGUARDANDO_PREPARO:2", "PRONTO:3");
        admin().update("UPDATE fastpay.job SET status = 'PENDING', next_run_at = now() WHERE kind = ? AND dedup_key = ?",
                Orders.FORWARDING_JOB, pedido);
        rodarJobs();
        assertThat(producaoDo(pedidoId)).containsExactly("AGUARDANDO_PREPARO:2", "PRONTO:3");
        gabi.get("/api/v1/orders/operations/" + chave).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pedido))
                .andExpect(jsonPath("$.forwarding").value("CONCLUIDO"));

        // Mudança de preço no catálogo não altera o enviado; o mesmo produto aparece com os dois preços (FR-16).
        admin().update("UPDATE fastpay.product SET price = 39.90 WHERE id = ?", batata);
        relogio.advance(Duration.ofSeconds(1));
        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(batata, 1, null, "39.90")))
                .andExpect(status().isOk());
        gabi.get("/api/v1/orders?tabId=" + comanda).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].lines[0].unitPrice").value("35.90"))
                .andExpect(jsonPath("$[1].lines[0].unitPrice").value("39.90"));
    }

    @Test
    void envioRepetidoComAMesmaChaveNaoDuplica() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");
        UUID chave = UUID.randomUUID();
        String corpo = envio(comanda, chave, linha(batata, 1, null, "35.90"), linha(agua, 2, null, "5.00"));

        String primeiro = JsonPath.read(gabi.post("/api/v1/orders", corpo).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.order.id");
        // Clique duplo ou resposta perdida: a mesma operação devolve o mesmo pedido.
        gabi.post("/api/v1/orders", corpo).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.order.id").value(primeiro));

        // Reenvio simultâneo da mesma operação por várias abas/conexões da mesma pessoa.
        UUID outraChave = UUID.randomUUID();
        String outroCorpo = envio(comanda, outraChave, linha(chopp, 4, "gelado", "9.90"));
        List<Callable<ResultActions>> tentativas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Browser aba = login.inUnit(garcom, loja);
            tentativas.add(() -> aba.post("/api/v1/orders", outroCorpo));
        }
        List<String> ids = new ArrayList<>();
        for (ResultActions r : emParalelo(tentativas)) {
            ids.add(JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                    "$.order.id"));
        }
        assertThat(ids).hasSize(5).containsOnly(ids.getFirst());

        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.customer_order WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda))).isEqualTo(2);
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ?
                """, Integer.class, UUID.fromString(comanda))).isEqualTo(3);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.job WHERE kind = ? AND dedup_key IN (?, ?)",
                Integer.class, Orders.FORWARDING_JOB, primeiro, ids.getFirst())).isEqualTo(2);

        // A mesma chave com outro conteúdo, outra comanda ou outra pessoa é recusada.
        gabi.post("/api/v1/orders", envio(comanda, chave, linha(batata, 2, null, "35.90")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHAVE_REUTILIZADA"));
        String outraComanda = comanda(gabi, "Beto");
        gabi.post("/api/v1/orders", envio(outraComanda, chave, linha(batata, 1, null, "35.90"),
                linha(agua, 2, null, "5.00"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAVE_REUTILIZADA"));
        Browser gui = login.inUnit(outroGarcom, loja);
        gui.post("/api/v1/orders", corpo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAVE_REUTILIZADA"));
        // Só quem enviou consulta a operação.
        gui.get("/api/v1/orders/operations/" + chave).andExpect(status().isNotFound());
    }

    @Test
    void precoMudadoOuEsgotadoMantemTudoNoRascunho() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");

        // Preço mudou entre montar e enviar: nada é gravado; a resposta traz o preço atual (CA-05).
        admin().update("UPDATE fastpay.product SET price = 37.50 WHERE id = ?", batata);
        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(chopp, 1, null, "9.90"),
                linha(batata, 1, null, "35.90"), linha(batata, 2, null, "35.90")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_PRECISA_REVISAO"))
                .andExpect(jsonPath("$.unavailable.length()").value(0))
                .andExpect(jsonPath("$.priceChanges.length()").value(1))
                .andExpect(jsonPath("$.priceChanges[0].productId").value(batata.toString()))
                .andExpect(jsonPath("$.priceChanges[0].seenPrice").value("35.90"))
                .andExpect(jsonPath("$.priceChanges[0].currentPrice").value("37.50"));
        assertThat(pedidosDa(comanda)).isZero();

        // Esgotado, arquivado ou de outra unidade: o pedido inteiro fica no rascunho, sem envio parcial (FR-14).
        admin().update("UPDATE fastpay.product SET available = false WHERE id = ?", chopp);
        UUID arquivado = produto(loja, "Caldo", "18.00", cozinha);
        admin().update("UPDATE fastpay.product SET archived_at = now() WHERE id = ?", arquivado);
        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00"),
                linha(chopp, 1, null, "9.90"), linha(arquivado, 1, null, "18.00"),
                linha(produtoDaOutra, 1, null, "12.00"), linha(batata, 1, null, "35.90")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_PRECISA_REVISAO"))
                .andExpect(jsonPath("$.unavailable.length()").value(3))
                .andExpect(jsonPath("$.unavailable[0]").value(chopp.toString()))
                .andExpect(jsonPath("$.unavailable[1]").value(arquivado.toString()))
                .andExpect(jsonPath("$.unavailable[2]").value(produtoDaOutra.toString()))
                .andExpect(jsonPath("$.priceChanges[0].currentPrice").value("37.50"));
        assertThat(pedidosDa(comanda)).isZero();
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.job WHERE establishment_id = ? AND kind = ?",
                Integer.class, loja, Orders.FORWARDING_JOB)).isZero();

        // Revisado (removido o esgotado, reconfirmado o preço), sai inteiro.
        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00"),
                linha(batata, 1, null, "37.50"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.order.lines[1].unitPrice").value("37.50"));
        // Pedidos anteriores não são cancelados quando o produto esgota depois (FR-14).
        admin().update("UPDATE fastpay.product SET available = false WHERE id = ?", agua);
        gabi.get("/api/v1/orders?tabId=" + comanda).andExpect(jsonPath("$[0].lines.length()").value(2));
    }

    @Test
    void fechamentoConcorrenteOuEntraAntesOuRecusaOEnvio() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");
        UUID tab = UUID.fromString(comanda);

        // Contrato com o fechamento (H28/H29): quem fecha trava a linha da comanda antes de consolidar. Um envio que
        // chega durante o fechamento espera a trava e, com a comanda encerrada, é recusado sem gravar nada (CA-07).
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection fechamento = dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .getConnection()) {
            fechamento.setAutoCommit(false);
            try (PreparedStatement trava = fechamento.prepareStatement(
                    "SELECT id FROM fastpay.tab WHERE id = ? FOR UPDATE")) {
                trava.setObject(1, tab);
                trava.executeQuery();
            }
            Future<MvcResult> envio = pool.submit(() -> gabi.post("/api/v1/orders",
                    envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00"))).andReturn());
            esperarEnvioTravado();
            try (PreparedStatement fecha = fechamento.prepareStatement(
                    "UPDATE fastpay.tab SET closed_at = ? WHERE id = ?")) {
                fecha.setTimestamp(1, Timestamp.from(Instant.now()));
                fecha.setObject(2, tab);
                fecha.executeUpdate();
            }
            fechamento.commit();
            MvcResult resultado = envio.get(30, TimeUnit.SECONDS);
            assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
            assertThat(resultado.getResponse().getContentAsString()).contains("COMANDA_ENCERRADA");
        } finally {
            pool.shutdownNow();
        }
        assertThat(pedidosDa(comanda)).isZero();

        // Na ordem inversa, o envio confirmado antes da trava do fechamento já está na comanda quando ela consolida.
        String outra = comanda(gabi, "Beto");
        gabi.post("/api/v1/orders", envio(outra, UUID.randomUUID(), linha(agua, 2, null, "5.00")))
                .andExpect(status().isOk());
        assertThat(admin().queryForObject("""
                SELECT sum(a.quantity) FROM fastpay.tab_allocation a WHERE a.tab_id = ?
                """, Integer.class, UUID.fromString(outra))).isEqualTo(2);

        // Encerrada continua recusando; a operação já gravada ainda é consultável e repetível.
        UUID chave = UUID.randomUUID();
        String corpo = envio(outra, chave, linha(agua, 1, null, "5.00"));
        gabi.post("/api/v1/orders", corpo).andExpect(status().isOk());
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(outra));
        gabi.post("/api/v1/orders", corpo).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        gabi.post("/api/v1/orders", envio(outra, UUID.randomUUID(), linha(agua, 1, null, "5.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"));
    }

    @Test
    void limitesExatosDoEnvio() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");

        String[] cinquenta = new String[50];
        for (int i = 0; i < 50; i++) {
            cinquenta[i] = linha(agua, 1, null, "5.00");
        }
        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), cinquenta)).andExpect(status().isOk())
                .andExpect(jsonPath("$.order.lines.length()").value(50));
        String[] cinquentaEUma = new String[51];
        for (int i = 0; i < 51; i++) {
            cinquentaEUma[i] = linha(agua, 1, null, "5.00");
        }
        invalido(gabi, envio(comanda, UUID.randomUUID(), cinquentaEUma), "lines");
        invalido(gabi, envio(comanda, UUID.randomUUID()), "lines");

        gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(agua, 99, "x".repeat(140), "5.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.lines[0].lineTotal").value("495.00"));
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 100, null, "5.00")), "lines[0].quantity");
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 0, null, "5.00")), "lines[0].quantity");
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00"),
                linha(agua, 1, "y".repeat(141), "5.00")), "lines[1].note");
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 1, "linha\\noutra", "5.00")), "lines[0].note");
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.001")), "lines[0].expectedPrice");
        invalido(gabi, envio(comanda, UUID.randomUUID(), linha(agua, 1, null, null)), "lines[0].expectedPrice");
        invalido(gabi, "{\"tabId\":\"" + comanda + "\",\"lines\":[" + linha(agua, 1, null, "5.00") + "]}",
                "operationKey");
        assertThat(pedidosDa(comanda)).isEqualTo(2);
    }

    @Test
    void permissaoUnidadeEFalhaDeEncaminhamentoReprocessavel() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");

        Browser paulo = login.inUnit(producao, loja);
        paulo.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        paulo.get("/api/v1/orders?tabId=" + comanda).andExpect(status().isForbidden());
        new Browser(mvc).post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(agua, 1, null, "5.00")))
                .andExpect(status().isUnauthorized());

        // Comanda de outra unidade não existe para quem está nesta (RLS).
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        otto.post("/api/v1/orders", envio(comanda, UUID.randomUUID(), linha(produtoDaOutra, 1, null, "12.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        otto.get("/api/v1/orders?tabId=" + comanda).andExpect(status().isNotFound());

        // Encaminhamento que esgotou as tentativas aparece no pedido e é reprocessado sem outro lançamento (FR-15).
        String pedido = JsonPath.read(gabi.post("/api/v1/orders", envio(comanda, UUID.randomUUID(),
                linha(batata, 1, null, "35.90"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.order.id");
        gabi.post("/api/v1/orders/" + pedido + "/forwarding/retry", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCAMINHAMENTO_SEM_FALHA"));
        admin().update("""
                UPDATE fastpay.job SET status = 'EXHAUSTED', attempts = 5, last_error = 'Falha simulada'
                 WHERE kind = ? AND dedup_key = ?
                """, Orders.FORWARDING_JOB, pedido);
        gabi.get("/api/v1/orders?tabId=" + comanda).andExpect(jsonPath("$[0].forwarding").value("FALHOU"));
        otto.post("/api/v1/orders/" + pedido + "/forwarding/retry", "{}").andExpect(status().isNotFound());
        gabi.post("/api/v1/orders/" + pedido + "/forwarding/retry", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.forwarding").value("PENDENTE"));
        rodarJobs();
        gabi.get("/api/v1/orders?tabId=" + comanda).andExpect(jsonPath("$[0].forwarding").value("CONCLUIDO"));
        assertThat(producaoDo(UUID.fromString(pedido))).containsExactly("AGUARDANDO_PREPARO:1");
        assertThat(pedidosDa(comanda)).isEqualTo(1);
        assertThat(admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? ORDER BY occurred_at",
                String.class, UUID.fromString(pedido))).containsExactly("pedido.enviado",
                        "pedido.encaminhamento_retomado");
    }

    // Apoio

    private void rodarJobs() {
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
    }

    private void esperarEnvioTravado() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer esperando = admin().queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%FROM tab t%'
                    """, Integer.class);
            if (esperando != null && esperando > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("O envio não chegou a esperar a trava da comanda.");
    }

    private List<String> producaoDo(UUID pedido) {
        return admin().queryForList("""
                SELECT p.status || ':' || p.quantity FROM fastpay.production_line p
                  JOIN fastpay.order_line l ON l.id = p.order_line_id
                 WHERE l.order_id = ? ORDER BY l.position
                """, String.class, pedido);
    }

    private int pedidosDa(String comanda) {
        Integer n = admin().queryForObject("SELECT count(*) FROM fastpay.customer_order WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda));
        return n == null ? 0 : n;
    }

    private static void invalido(Browser quem, String corpo, String campo) throws Exception {
        quem.post("/api/v1/orders", corpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.field").value(campo));
    }

    private String comanda(Browser quem, String nome) throws Exception {
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private UUID setor(UUID unidade, String nome) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.sector (id, establishment_id, name, created_by, created_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, now(), ?, now())
                """, id, unidade, nome, dono, dono);
        return id;
    }

    private UUID produto(UUID unidade, String nome, String preco, UUID setor) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.product (id, establishment_id, name, category, price, sector_id, created_by,
                                             created_at, updated_by, updated_at)
                VALUES (?, ?, ?, 'Geral', ?::numeric, ?, ?, now(), ?, now())
                """, id, unidade, nome, preco, setor, dono, dono);
        return id;
    }

    private static String linha(UUID produto, Integer quantidade, String observacao, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":"
                + (observacao == null ? "null" : "\"" + observacao + "\"") + ",\"expectedPrice\":"
                + (preco == null ? "null" : "\"" + preco + "\"") + "}";
    }

    private static String envio(String comanda, UUID chave, String... linhas) {
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        return "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + chave + "\",\"lines\":" + itens + "}";
    }

    private static <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return tarefa.call();
                }));
            }
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }
}
