package tech.malyah.fastpay.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
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
 * Desfazer entrega e ocorrência pela API (H24, FR-70, D-07), com PostgreSQL real, RLS, Spring Session e CSRF. Cobre a
 * validação exigida no backlog: destinos múltiplos com um fechado, corrida com o fechamento (a trava da comanda),
 * permissão revogada e nova entrega substituindo a amostra antiga.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class DeliveryCorrectionIntegrationTest extends PostgresIntegrationTest {

    private static final String ENTREGAS = "/api/v1/production/deliveries";
    private static final String ENTREGUES = "/api/v1/production/delivered";

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
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID gerenteDaOutra;
    UUID vinculoGarcom;
    UUID cozinha;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Entrega");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        gerenteDaOutra = fixtures.identity("Olga Outra");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(gerenteDaOutra, outraLoja, MembershipRole.GERENTE);
        cozinha = setor(loja, "Cozinha");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void desfazerVoltaAProntoComHistoricoENovaEntregaSubstituiAAmostra() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Ana");
        enviar(gabi, comanda, linha(batata, 2, "35.90"));
        String linha = idDe(paulo.get("/api/v1/production/queue").andReturn().getResponse().getContentAsString(),
                "Batata frita");
        String caminho = "/api/v1/production/lines/" + linha;
        paulo.post(caminho + "/start", "{\"version\":0}").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(5));
        paulo.post(caminho + "/ready", "{\"version\":1}").andExpect(status().isOk());
        Instant pronto = relogio.instant();

        // Ainda não entregue: nada a desfazer.
        gui.post(caminho + "/undo-delivery", corpo(2, "Entregou na mesa errada")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_NAO_ENTREGUE"));

        relogio.advance(Duration.ofMinutes(1));
        gabi.post(caminho + "/deliver", "{\"version\":2}").andExpect(status().isOk());
        String entregaErrada = ultimaEntrega(linha);

        // Garçom sem delegação não vê nem desfaz, nem por chamada direta (CA-04).
        gabi.get(ENTREGUES).andExpect(status().isForbidden());
        gabi.post(caminho + "/undo-delivery", corpo(3, "Entregou na mesa errada")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        // Outra unidade não enxerga a linha.
        login.inUnit(gerenteDaOutra, outraLoja).post(caminho + "/undo-delivery", corpo(3, "Entregou errado"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINHA_NAO_ENCONTRADA"));
        gui.post(caminho + "/undo-delivery", corpo(3, "ops")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        gui.post(caminho + "/undo-delivery", "{\"reason\":\"Entregou na mesa errada\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("version"));
        gui.post(caminho + "/undo-delivery", corpo(2, "Entregou na mesa errada")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));

        gui.get(ENTREGUES).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].id").value(linha))
                .andExpect(jsonPath("$.lines[0].status").value("ENTREGUE"))
                .andExpect(jsonPath("$.lines[0].destinations[0].closed").value(false));

        relogio.advance(Duration.ofMinutes(1));
        Instant desfeita = relogio.instant();
        gui.post(caminho + "/undo-delivery", corpo(3, "Entregou na mesa errada")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRONTO"))
                .andExpect(jsonPath("$.deliveredAt").value(nullValue()))
                .andExpect(jsonPath("$.readyAt").value(pronto.toString()))
                .andExpect(jsonPath("$.statusSince").value(desfeita.toString()))
                .andExpect(jsonPath("$.version").value(4));
        // Volta para as entregas, com aviso novo de item pronto (H20); a antiga continua concluída.
        gabi.get(ENTREGAS).andExpect(jsonPath("$.lines[0].id").value(linha));
        assertThat(chamados(linha)).containsExactly("ITEM_PRONTO:ENTREGUE", "ITEM_PRONTO:null");
        gui.get(ENTREGUES).andExpect(jsonPath("$.lines.length()").value(0));
        // Repetir: a linha já não está entregue.
        gui.post(caminho + "/undo-delivery", corpo(4, "Entregou na mesa errada")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_NAO_ENTREGUE"));

        // Nova entrega válida redefine o tempo; a errada fica no histórico, invalidada (FR-70, FR-75).
        relogio.advance(Duration.ofMinutes(2));
        Instant certa = relogio.instant();
        gabi.post(caminho + "/deliver", "{\"version\":4}").andExpect(status().isOk());
        assertThat(admin().queryForObject("SELECT delivered_at FROM fastpay.production_line WHERE id = ?",
                Timestamp.class, UUID.fromString(linha)).toInstant()).isEqualTo(certa);
        assertThat(historico(linha)).containsExactly("INICIADO:AGUARDANDO_PREPARO>EM_PREPARO",
                "PRONTO:EM_PREPARO>PRONTO", "ENTREGUE:PRONTO>ENTREGUE", "ENTREGA_DESFEITA:ENTREGUE>PRONTO",
                "ENTREGUE:PRONTO>ENTREGUE");
        assertThat(admin().queryForObject("""
                SELECT invalidates_event_id::text || ':' || reason || ':' || performed_by::text
                  FROM fastpay.production_event WHERE production_line_id = ? AND action = 'ENTREGA_DESFEITA'
                """, String.class, UUID.fromString(linha)))
                .isEqualTo(entregaErrada + ":Entregou na mesa errada:" + gerente);
        // Amostra de entrega válida: só a nova.
        assertThat(admin().queryForList("""
                SELECT e.performed_at FROM fastpay.production_event e
                 WHERE e.production_line_id = ? AND e.action = 'ENTREGUE'
                   AND NOT EXISTS (SELECT 1 FROM fastpay.production_event d WHERE d.invalidates_event_id = e.id)
                """, Timestamp.class, UUID.fromString(linha))).extracting(Timestamp::toInstant).containsExactly(certa);
        assertThat(auditoria(UUID.fromString(linha))).contains("entrega.desfeita");

        // Garçom com a delegação desfaz; revogada, não desfaz mais (permissão lida a cada chamada).
        delegar(vinculoGarcom, "DESFAZER_ENTREGA");
        gabi.post(caminho + "/undo-delivery", corpo(5, "Cliente era outra mesa")).andExpect(status().isOk());
        gabi.post(caminho + "/deliver", "{\"version\":6}").andExpect(status().isOk());
        admin().update("""
                UPDATE fastpay.membership_permission SET revoked_at = now(), revoked_by = ?
                 WHERE membership_id = ? AND permission = 'DESFAZER_ENTREGA'
                """, dono, vinculoGarcom);
        gabi.post(caminho + "/undo-delivery", corpo(7, "Cliente era outra mesa")).andExpect(status().isForbidden());
        gabi.get(ENTREGUES).andExpect(status().isForbidden());
        assertThat(etapa(linha)).isEqualTo("ENTREGUE:7");
    }

    @Test
    void destinosMultiplosComUmEncerradoSoRegistramOcorrencia() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String ana = comanda(gabi, "Ana");
        String bia = comanda(gabi, "Bia");
        enviar(gabi, ana, linha(chopp, 2, "9.90"));
        // Duas unidades em duas comandas (o que a transferência da H25 fará): uma em cada.
        dividir(ana, bia, "Chopp 300 ml");
        String linha = idDe(gabi.get(ENTREGAS).andReturn().getResponse().getContentAsString(), "Chopp 300 ml");
        String caminho = "/api/v1/production/lines/" + linha;
        gabi.post(caminho + "/deliver", "{\"version\":0}").andExpect(status().isOk());

        // Todas abertas: ocorrência não cabe, o caminho é desfazer.
        gui.post(caminho + "/delivery-occurrence", corpo(1, "Entregou na mesa errada"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTREGA_PODE_SER_DESFEITA"));

        // Bia pagou e foi encerrada: desfazer não reabre nem devolve.
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(bia));
        gui.get(ENTREGUES).andExpect(jsonPath("$.lines[0].destinations.length()").value(2))
                .andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Bia')].closed").value(true))
                .andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Ana')].closed").value(false));
        gui.post(caminho + "/undo-delivery", corpo(1, "Entregou na mesa errada")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"))
                .andExpect(jsonPath("$.detail").value(containsString("ocorrência")));
        assertThat(etapa(linha)).isEqualTo("ENTREGUE:1");

        relogio.advance(Duration.ofSeconds(10));
        Instant ocorrencia = relogio.instant();
        gui.post(caminho + "/delivery-occurrence", corpo(1, "Bia recebeu chopp quente")).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.line.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.line.version").value(1));
        // A linha, a conta e o pagamento ficam; o histórico ganha a ocorrência, sem devolução automática.
        assertThat(etapa(linha)).isEqualTo("ENTREGUE:1");
        assertThat(historico(linha)).containsExactly("ENTREGUE:PRONTO>ENTREGUE",
                "OCORRENCIA_ENTREGA:ENTREGUE>ENTREGUE");
        assertThat(admin().queryForObject("""
                SELECT reason || ':' || performed_at FROM fastpay.production_event
                 WHERE production_line_id = ? AND action = 'OCORRENCIA_ENTREGA'
                """, String.class, UUID.fromString(linha))).startsWith("Bia recebeu chopp quente:");
        assertThat(admin().queryForObject("""
                SELECT performed_at FROM fastpay.production_event
                 WHERE production_line_id = ? AND action = 'OCORRENCIA_ENTREGA'
                """, Timestamp.class, UUID.fromString(linha)).toInstant()).isEqualTo(ocorrencia);
        assertThat(auditoria(UUID.fromString(linha))).contains("entrega.ocorrencia");
        gui.post(caminho + "/delivery-occurrence", corpo(1, "ops")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        gabi.post(caminho + "/delivery-occurrence", corpo(1, "Bia recebeu chopp quente"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corridaComFechamentoEsperaATravaDaComanda() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Caio");
        enviar(gabi, comanda, linha(chopp, 1, "9.90"));
        String linha = idDe(gabi.get(ENTREGAS).andReturn().getResponse().getContentAsString(), "Chopp 300 ml");
        String caminho = "/api/v1/production/lines/" + linha;
        gabi.post(caminho + "/deliver", "{\"version\":0}").andExpect(status().isOk());

        // O fechamento (H28) trava a comanda e encerra; o desfazer espera a trava e vê a comanda encerrada.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection fechamento = admin().getDataSource().getConnection()) {
            fechamento.setAutoCommit(false);
            try (PreparedStatement trava = fechamento.prepareStatement(
                    "SELECT id FROM fastpay.tab WHERE id = ? FOR UPDATE")) {
                trava.setObject(1, UUID.fromString(comanda));
                trava.executeQuery().close();
            }
            Future<String> desfazer = pool.submit(() -> gui.post(caminho + "/undo-delivery",
                    corpo(1, "Entregou na mesa errada")).andReturn().getResponse().getContentAsString());
            esperarTrava();
            assertThat(desfazer.isDone()).isFalse();
            try (PreparedStatement encerra = fechamento.prepareStatement(
                    "UPDATE fastpay.tab SET closed_at = now() WHERE id = ?")) {
                encerra.setObject(1, UUID.fromString(comanda));
                encerra.executeUpdate();
            }
            fechamento.commit();
            assertThat(JsonPath.<String>read(desfazer.get(30, TimeUnit.SECONDS), "$.code"))
                    .isEqualTo("COMANDA_ENCERRADA");
        } finally {
            pool.shutdownNow();
        }
        assertThat(etapa(linha)).isEqualTo("ENTREGUE:1");
        assertThat(historico(linha)).containsExactly("ENTREGUE:PRONTO>ENTREGUE");
    }

    @Test
    void entregueComTudoCanceladoNaoTemOQueDesfazer() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Duda");
        enviar(gabi, comanda, linha(chopp, 1, "9.90"));
        String linha = idDe(gabi.get(ENTREGAS).andReturn().getResponse().getContentAsString(), "Chopp 300 ml");
        String caminho = "/api/v1/production/lines/" + linha;
        gabi.post(caminho + "/deliver", "{\"version\":0}").andExpect(status().isOk());
        gui.post("/api/v1/tabs/" + comanda + "/items/" + linhaDoPedido(comanda, "Chopp 300 ml") + "/cancel",
                "{\"quantity\":1,\"expectedQuantity\":1,\"reason\":\"Bebida aguada\"}").andExpect(status().isOk());
        gui.post(caminho + "/undo-delivery", corpo(2, "Entregou na mesa errada")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_CANCELADO"));
        assertThat(etapa(linha)).isEqualTo("ENTREGUE:2");
    }

    // Apoio

    private static String corpo(long versao, String motivo) {
        return "{\"version\":" + versao + ",\"reason\":\"" + motivo + "\"}";
    }

    /** Espera alguma sessão do banco ficar bloqueada numa trava (o desfazer esperando a comanda). */
    private void esperarTrava() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer esperando = admin().queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (esperando != null && esperando > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("O desfazer não esperou a trava da comanda.");
    }

    /** Divide a alocação da linha: uma unidade fica em {@code de}, uma vai para {@code para}. */
    private void dividir(String de, String para, String produto) {
        UUID linhaPedido = UUID.fromString(linhaDoPedido(de, produto));
        admin().update("UPDATE fastpay.tab_allocation SET quantity = 1 WHERE order_line_id = ?", linhaPedido);
        admin().update("""
                INSERT INTO fastpay.tab_allocation (id, establishment_id, order_line_id, tab_id, quantity, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """, UUID.randomUUID(), loja, linhaPedido, UUID.fromString(para));
    }

    private String etapa(String linha) {
        return admin().queryForObject("SELECT status || ':' || version FROM fastpay.production_line WHERE id = ?",
                String.class, UUID.fromString(linha));
    }

    private String ultimaEntrega(String linha) {
        return admin().queryForObject("""
                SELECT id::text FROM fastpay.production_event WHERE production_line_id = ? AND action = 'ENTREGUE'
                 ORDER BY line_version DESC LIMIT 1
                """, String.class, UUID.fromString(linha));
    }

    private List<String> historico(String linha) {
        return admin().queryForList("""
                SELECT action || ':' || from_status || '>' || to_status FROM fastpay.production_event
                 WHERE production_line_id = ? ORDER BY performed_at, line_version
                """, String.class, UUID.fromString(linha));
    }

    private List<String> chamados(String linha) {
        return admin().queryForList("""
                SELECT kind || ':' || coalesce(resolution, 'null') FROM fastpay.attendance_call
                 WHERE production_line_id = ? ORDER BY created_at
                """, String.class, UUID.fromString(linha));
    }

    private List<String> auditoria(UUID sujeito) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? "
                + "ORDER BY occurred_at, action", String.class, sujeito);
    }

    private String linhaDoPedido(String comanda, String produto) {
        return admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ? AND l.product_name = ?
                """, String.class, UUID.fromString(comanda), produto);
    }

    private static String idDe(String board, String produto) {
        List<String> ids = JsonPath.read(board, "$.lines[?(@.productName == '" + produto + "')].id");
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private void delegar(UUID vinculo, String permissao) {
        admin().update("""
                INSERT INTO fastpay.membership_permission (id, establishment_id, membership_id, permission, granted_by,
                       granted_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), loja, vinculo, permissao, dono);
    }

    private void rodarJobs() {
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
    }

    private String comanda(Browser quem, String nome) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void enviar(Browser quem, String comanda, String... linhas) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        quem.post("/api/v1/orders", "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID()
                + "\",\"lines\":" + itens + "}").andExpect(status().isOk());
        rodarJobs();
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

    private static String linha(UUID produto, int quantidade, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":null,\"expectedPrice\":\""
                + preco + "\"}";
    }
}
