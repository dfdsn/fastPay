package tech.malyah.fastpay.bills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Conferência, fechamento e reabertura pela API (H28, FR-31–FR-33, CA-07, CA-08, CA-11), com PostgreSQL real, RLS,
 * Spring Session e CSRF. Cobre a validação exigida no backlog: corridas de envio, cancelamento e transferência contra
 * o fechamento, item pronto não entregue, retirada do serviço na conferência e reabertura com o rascunho preservado
 * (o pedido recusado em fechamento volta a ser aceito depois de reabrir). A reabertura com cobrança incerta é contrato
 * da H30, que ainda não existe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class ClosingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    Closings fechamentos;

    @Autowired
    TenantTransactions tenants;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID vinculoGarcom;
    UUID vinculoGerente;
    UUID cozinha;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Fechamento");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        cozinha = setor("Cozinha");
        batata = produto("Batata frita", "35.90", cozinha);
        chopp = produto("Chopp 300 ml", "9.90", null);
    }

    @Test
    void itemNaoEntregueImpedeEFechamentoTravaLancamentosAteReabrir() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser paulo = login.inUnit(producao, loja);
        String ana = comanda(gabi, "Ana");
        String bia = comanda(gabi, "Bia");
        enviar(gabi, ana, linha(batata, 1, "35.90"), linha(chopp, 2, "9.90"));
        enviar(gabi, bia, linha(chopp, 1, "9.90"));
        String batataAna = producaoDe(ana, "Batata frita");

        // Aguardando preparo e pronto (o chopp, sem preparo) impedem (FR-31).
        gabi.get(fechamento(ana)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABERTA"))
                .andExpect(jsonPath("$.total").value("61.27"))
                .andExpect(jsonPath("$.canStart").value(false))
                .andExpect(jsonPath("$.blockers.pendingItems.length()").value(2))
                .andExpect(jsonPath("$.blockers.pendingItems[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.blockers.pendingItems[0].stage").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.blockers.pendingItems[1].stage").value("PRONTO"));
        gabi.post(fechamento(ana), total("61.27")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITENS_PENDENTES"));

        // Impossível não resolvido também impede.
        paulo.post(linhaProducao(batataAna) + "/impossible", "{\"version\":0,\"reason\":\"Acabou a batata\"}")
                .andExpect(status().isOk());
        gabi.get(fechamento(ana)).andExpect(jsonPath("$.blockers.pendingItems[0].stage").value("IMPOSSIVEL"));
        paulo.post(linhaProducao(batataAna) + "/impossible/withdraw", "{\"version\":1}").andExpect(status().isOk());

        // CA-08: depois de entregar tudo, a elegibilidade é reavaliada.
        entregarTudo(gabi, paulo, ana);
        gabi.get(fechamento(ana)).andExpect(jsonPath("$.canStart").value(true))
                .andExpect(jsonPath("$.blockers.pendingItems.length()").value(0))
                .andExpect(jsonPath("$.blockers.openIssue").value(false))
                .andExpect(jsonPath("$.blockers.discountReview").value(false));

        // O total visto precisa ser o de agora.
        gabi.post(fechamento(ana), total("61,27")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("expectedTotal"));
        gabi.post(fechamento(ana), total("50.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTA_MUDOU"));
        String aberto = gabi.post(fechamento(ana), total("61.27")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EM_FECHAMENTO"))
                .andExpect(jsonPath("$.version.number").value(1))
                .andExpect(jsonPath("$.version.total").value("61.27"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.blockers").value(nullValue()))
                .andExpect(jsonPath("$.canReopen").value(false))
                .andReturn().getResponse().getContentAsString();
        String closingId = JsonPath.read(aberto, "$.closingId");
        gabi.post(fechamento(ana), total("61.27")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gabi.get("/api/v1/tabs/" + ana).andExpect(jsonPath("$.closingSince").isNotEmpty());
        gabi.get(conta(ana)).andExpect(jsonPath("$.closing").value(true))
                .andExpect(jsonPath("$.policy.canGrant").value(false));

        // FR-32: em fechamento, nada entra nem sai; o rascunho fica no aparelho.
        String pedido = pedido(ana, linha(chopp, 1, "9.90"));
        gabi.post("/api/v1/orders", pedido).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gui.post(item(ana, "Chopp 300 ml") + "/cancel", "{\"quantity\":1,\"expectedQuantity\":2,\"reason\":\"Cliente "
                + "desistiu\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gui.post(item(ana, "Chopp 300 ml") + "/transfer", transferencia(bia, 1, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gui.post(item(bia, "Chopp 300 ml") + "/transfer", transferencia(ana, 1, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESTINO_EM_FECHAMENTO"));
        gui.put(conta(ana) + "/discount", "{\"currentDiscountId\":null,\"kind\":\"FIXO\",\"value\":\"5.00\","
                + "\"reason\":\"Demora no pedido\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gui.post(linhaProducao(batataAna) + "/undo-delivery", "{\"version\":" + versaoProducao(batataAna)
                + ",\"reason\":\"Entregou na mesa errada\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));
        gui.post("/api/v1/tabs/" + ana + "/visit-continuation", "{\"previousTabId\":\"" + bia
                + "\",\"stayConfirmed\":true,\"version\":" + versao(gui, ana) + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_EM_FECHAMENTO"));

        // CA-11: o serviço sai na conferência, sem autorização, e a conta consolidada ganha a versão 2.
        gabi.put(conta(ana) + "/service", "{\"waived\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("55.70"));
        gabi.get(fechamento(ana)).andExpect(jsonPath("$.version.number").value(2))
                .andExpect(jsonPath("$.version.total").value("55.70"));
        assertThat(versoes(ana)).containsExactly("1:61.27:false", "2:55.70:true");

        // Reabrir (FR-33): só com a permissão e motivo, no fechamento que a tela mostrava.
        gabi.post(fechamento(ana) + "/reopen", reabrir(closingId, "Cliente quer mais um chopp"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        gui.get(fechamento(ana)).andExpect(jsonPath("$.canReopen").value(true));
        gui.post(fechamento(ana) + "/reopen", reabrir(closingId, "ops")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        gui.post(fechamento(ana) + "/reopen", reabrir(UUID.randomUUID().toString(), "Cliente quer mais um chopp"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gui.post(fechamento(ana) + "/reopen", reabrir(closingId, "Cliente quer mais um chopp"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABERTA"))
                .andExpect(jsonPath("$.closingId").value(nullValue()));
        gui.post(fechamento(ana) + "/reopen", reabrir(closingId, "Cliente quer mais um chopp"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_NAO_EM_FECHAMENTO"));
        assertThat(auditoria(UUID.fromString(ana), "fechamento.%")).containsExactly("fechamento.iniciado",
                "fechamento.reaberto");
        assertThat(admin().queryForObject("SELECT reopen_reason FROM fastpay.tab_closing WHERE id = ?", String.class,
                UUID.fromString(closingId))).isEqualTo("Cliente quer mais um chopp");

        // Reaberta: o rascunho recusado é aceito, com a mesma configuração e a escolha do serviço preservadas.
        gabi.post("/api/v1/orders", pedido).andExpect(status().isOk());
        gabi.get(conta(ana)).andExpect(jsonPath("$.closing").value(false))
                .andExpect(jsonPath("$.consumption").value("65.60"))
                .andExpect(jsonPath("$.service.waived").value(true));

        // Garçom com a delegação também reabre.
        rodarJobs();
        entregarTudo(gabi, paulo, ana);
        String segundo = JsonPath.read(gabi.post(fechamento(ana), total("65.60")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.closingId");
        delegar(vinculoGarcom, "REABRIR_COMANDA");
        gabi.post(fechamento(ana) + "/reopen", reabrir(segundo, "Faltou lançar a sobremesa"))
                .andExpect(status().isOk());
    }

    @Test
    void clienteConfereRetiraOServicoEIniciaOFechamento() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String ana = comanda(gabi, "Ana");
        enviar(gabi, ana, linha(chopp, 3, "9.90"));
        Browser cliente = vincular(gabi, ana);
        String meu = "/api/v1/guest/tabs/" + ana + "/closing";

        // Sem o acesso da comanda: 404, como na H15.
        new Browser(mvc).get(meu).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        cliente.get(meu).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABERTA"))
                .andExpect(jsonPath("$.canStart").value(false))
                .andExpect(jsonPath("$.blockers.pendingItems[0].stage").value("PRONTO"));
        cliente.post(meu, total("32.67")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITENS_PENDENTES"));
        entregarTudo(gabi, null, ana);

        // Problema informado e não tratado impede (FR-24).
        String problema = JsonPath.read(cliente.post("/api/v1/guest/tabs/" + ana + "/issues",
                "{\"description\":\"Não pedi 3 chopps\"}").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.id");
        cliente.get(meu).andExpect(jsonPath("$.blockers.openIssue").value(true))
                .andExpect(jsonPath("$.canStart").value(false));
        cliente.post(meu, total("32.67")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_EM_ABERTO"));
        gabi.post("/api/v1/attendance/issues/" + problema + "/attend", "{}").andExpect(status().isOk());
        login.inUnit(gerente, loja).post("/api/v1/attendance/issues/" + problema + "/conclude",
                "{\"version\":1,\"resolution\":\"Explicado ao cliente\",\"correction\":\"SEM_CORRECAO\"}")
                .andExpect(status().isOk());

        // O cliente retira o serviço na conferência e fecha com o total que vê.
        cliente.put("/api/v1/guest/tabs/" + ana + "/bill/service", "{\"waived\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("29.70"));
        cliente.post(meu, total("29.70")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EM_FECHAMENTO"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.total").value("29.70"))
                .andExpect(jsonPath("$.canReopen").doesNotExist());
        assertThat(admin().queryForObject("""
                SELECT started_by IS NULL AND started_by_grant IS NOT NULL FROM fastpay.tab_closing WHERE tab_id = ?
                """, Boolean.class, UUID.fromString(ana))).isTrue();
        cliente.get("/api/v1/guest/tabs/" + ana + "/bill").andExpect(jsonPath("$.closing").value(true));

        // Ainda antes da cobrança, devolve o serviço: versão 2, que é a que vale (contrato da H30).
        cliente.put("/api/v1/guest/tabs/" + ana + "/bill/service", "{\"waived\":false}").andExpect(status().isOk());
        cliente.get(meu).andExpect(jsonPath("$.versionNumber").value(2)).andExpect(jsonPath("$.total").value("32.67"));
        Closings.Version vale = tenants.inEstablishment(loja,
                () -> fechamentos.currentVersionInCurrentUnit(UUID.fromString(ana))).orElseThrow();
        assertThat(vale.number()).isEqualTo(2);
        assertThat(vale.total()).isEqualTo("32.67");
        assertThat(versoes(ana)).containsExactly("1:29.70:true", "2:32.67:false");
    }

    @Test
    void descontoEmRevisaoImpedeOFechamento() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        limite(vinculoGerente, "20");
        String dani = comanda(gabi, "Dani");
        enviar(gabi, dani, linha(chopp, 3, "9.90"), linha(chopp, 2, "9.90"));
        // C = 49,50; 9,90 é 20%. Sem 2 chopps, 9,90 sobre 29,70 passa dos 20% de quem concedeu.
        gui.put(conta(dani) + "/discount", "{\"currentDiscountId\":null,\"kind\":\"FIXO\",\"value\":\"9.90\","
                + "\"reason\":\"Demora no pedido\"}").andExpect(status().isOk());
        String primeira = admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ? AND l.quantity = 2
                """, String.class, UUID.fromString(dani));
        gui.post("/api/v1/tabs/" + dani + "/items/" + primeira + "/cancel", "{\"quantity\":2,\"expectedQuantity\":2,"
                + "\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk());
        entregarTudo(gabi, null, dani);
        String total = JsonPath.read(gabi.get(fechamento(dani)).andExpect(jsonPath("$.blockers.discountReview")
                .value(true)).andExpect(jsonPath("$.canStart").value(false)).andReturn().getResponse()
                .getContentAsString(), "$.total");
        gabi.post(fechamento(dani), total(total)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESCONTO_EM_REVISAO"));
    }

    @Test
    void envioEFechamentoSimultaneosNuncaDeixamCobrancaDesatualizada() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        for (int rodada = 0; rodada < 4; rodada++) {
            String comanda = comanda(gabi, "Rodada " + rodada);
            enviar(gabi, comanda, linha(chopp, 1, "9.90"));
            entregarTudo(gabi, null, comanda);
            String pedido = pedido(comanda, linha(chopp, 1, "9.90"));
            List<String> resultados = emParalelo(List.of(
                    () -> "pedido:" + resposta(gabi.post("/api/v1/orders", pedido).andReturn().getResponse()),
                    () -> "fechamento:" + resposta(gui.post(fechamento(comanda), total("10.89")).andReturn()
                            .getResponse())));
            // CA-07: o item entra antes da consolidação (e o fechamento é recusado) ou o envio é impedido.
            assertThat(String.join(" ", resultados)).isIn("pedido:200 fechamento:ITENS_PENDENTES",
                    "pedido:COMANDA_EM_FECHAMENTO fechamento:200");
            Integer pedidos = admin().queryForObject("SELECT count(*)::int FROM fastpay.customer_order WHERE tab_id = ?",
                    Integer.class, UUID.fromString(comanda));
            Integer fechadas = admin().queryForObject("""
                    SELECT count(*)::int FROM fastpay.tab_closing WHERE tab_id = ? AND reopened_at IS NULL
                    """, Integer.class, UUID.fromString(comanda));
            assertThat(pedidos + fechadas).isEqualTo(2);
            rodarJobs();
        }
    }

    // Apoio

    private static String resposta(org.springframework.mock.web.MockHttpServletResponse r) throws Exception {
        return r.getStatus() == 200 ? "200" : JsonPath.read(r.getContentAsString(), "$.code");
    }

    /** Entrega tudo o que está pendente na comanda (a produção conclui o preparo antes, quando há setor). */
    private void entregarTudo(Browser garcomDaVez, Browser cozinheiro, String comanda) throws Exception {
        rodarJobs();
        for (Map<String, Object> l : admin().queryForList("""
                SELECT p.id, p.status FROM fastpay.production_line p
                  JOIN fastpay.tab_allocation a ON a.order_line_id = p.order_line_id
                 WHERE a.tab_id = ? AND a.quantity > 0 AND p.status NOT IN ('ENTREGUE', 'CANCELADA')
                """, UUID.fromString(comanda))) {
            String id = l.get("id").toString();
            if (!"PRONTO".equals(l.get("status"))) {
                if ("AGUARDANDO_PREPARO".equals(l.get("status"))) {
                    cozinheiro.post(linhaProducao(id) + "/start", "{\"version\":" + versaoProducao(id) + "}")
                            .andExpect(status().isOk());
                }
                cozinheiro.post(linhaProducao(id) + "/ready", "{\"version\":" + versaoProducao(id) + "}")
                        .andExpect(status().isOk());
            }
            garcomDaVez.post(linhaProducao(id) + "/deliver", "{\"version\":" + versaoProducao(id) + "}")
                    .andExpect(status().isOk());
        }
    }

    private List<String> versoes(String comanda) {
        return admin().queryForList("""
                SELECT number || ':' || total || ':' || service_waived FROM fastpay.bill_version WHERE tab_id = ?
                 ORDER BY created_at, number
                """, String.class, UUID.fromString(comanda));
    }

    private String producaoDe(String comanda, String produto) {
        return admin().queryForObject("""
                SELECT p.id::text FROM fastpay.production_line p JOIN fastpay.order_line l ON l.id = p.order_line_id
                  JOIN fastpay.customer_order o ON o.id = l.order_id WHERE o.tab_id = ? AND l.product_name = ?
                """, String.class, UUID.fromString(comanda), produto);
    }

    private long versaoProducao(String linha) {
        return admin().queryForObject("SELECT version FROM fastpay.production_line WHERE id = ?", Long.class,
                UUID.fromString(linha));
    }

    private String item(String comanda, String produto) {
        String linha = admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ? AND l.product_name = ? ORDER BY o.sent_at LIMIT 1
                """, String.class, UUID.fromString(comanda), produto);
        return "/api/v1/tabs/" + comanda + "/items/" + linha;
    }

    private long versao(Browser quem, String comanda) throws Exception {
        Number v = JsonPath.read(quem.get("/api/v1/tabs/" + comanda).andReturn().getResponse().getContentAsString(),
                "$.version");
        return v.longValue();
    }

    private static String linhaProducao(String id) {
        return "/api/v1/production/lines/" + id;
    }

    private static String fechamento(String comanda) {
        return "/api/v1/tabs/" + comanda + "/closing";
    }

    private static String conta(String comanda) {
        return "/api/v1/tabs/" + comanda + "/bill";
    }

    private static String total(String valor) {
        return "{\"expectedTotal\":\"" + valor + "\"}";
    }

    private static String reabrir(String fechamento, String motivo) {
        return "{\"closingId\":\"" + fechamento + "\",\"reason\":\"" + motivo + "\"}";
    }

    private static String transferencia(String destino, int quantidade, int esperado) {
        return "{\"toTabId\":\"" + destino + "\",\"quantity\":" + quantidade + ",\"expectedQuantity\":" + esperado
                + ",\"reason\":\"Dividiram a conta\"}";
    }

    private List<String> auditoria(UUID sujeito, String padrao) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? AND action LIKE ? "
                + "ORDER BY occurred_at, action", String.class, sujeito, padrao);
    }

    private void delegar(UUID vinculo, String permissao) {
        admin().update("""
                INSERT INTO fastpay.membership_permission (id, establishment_id, membership_id, permission, granted_by,
                       granted_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), loja, vinculo, permissao, dono);
    }

    private void limite(UUID vinculo, String percentual) {
        admin().update("UPDATE fastpay.membership SET discount_limit_percent = ?::numeric WHERE id = ?", percentual,
                vinculo);
    }

    private Browser vincular(Browser equipe, String comanda) throws Exception {
        String link = JsonPath.read(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.link");
        Browser cliente = new Browser(mvc);
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + link.substring(link.indexOf('#') + 1)
                + "\"}").andExpect(status().isOk());
        return cliente;
    }

    private String comanda(Browser quem, String nome) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String pedido(String comanda, String... linhas) {
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        return "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID() + "\",\"lines\":" + itens
                + "}";
    }

    private void enviar(Browser quem, String comanda, String... linhas) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        quem.post("/api/v1/orders", pedido(comanda, linhas)).andExpect(status().isOk());
        rodarJobs();
    }

    private void rodarJobs() {
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
    }

    private UUID setor(String nome) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.sector (id, establishment_id, name, created_by, created_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, now(), ?, now())
                """, id, loja, nome, dono, dono);
        return id;
    }

    private UUID produto(String nome, String preco, UUID setor) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.product (id, establishment_id, name, category, price, sector_id, created_by,
                                             created_at, updated_by, updated_at)
                VALUES (?, ?, ?, 'Geral', ?::numeric, ?, ?, now(), ?, now())
                """, id, loja, nome, preco, setor, dono, dono);
        return id;
    }

    private static String linha(UUID produto, int quantidade, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":null,\"expectedPrice\":\""
                + preco + "\"}";
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
