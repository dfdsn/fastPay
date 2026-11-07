package tech.malyah.fastpay.bills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Encerramento de conta zerada e de comanda abandonada vazia pela API (H29, FR-41, FR-42, FR-11, CA-20, D-02), com
 * PostgreSQL real, RLS, Spring Session e CSRF. Cobre a validação exigida no backlog: conta sem pedidos, desconto de
 * 100% com e sem serviço, couvert, problema aberto e a ausência de pagamento ou comissão fictícios (nenhuma tabela de
 * pagamento existe ainda; o encerramento só grava o próprio registro e a versão zerada da conta). Cobrança incerta é
 * contrato da H30, que ainda não existe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class ZeroClosingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID vinculoGerente;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Zero");
        dono = fixtures.identity("Dona Zélia");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        chopp = produto("Chopp 300 ml", "9.90");
    }

    @Test
    void clienteEncerraContaSemPedidosSemPagamentoEComPrazoDeAcesso() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String ana = comanda(gabi, "Ana");
        Browser cliente = vincular(gabi, ana);
        String meu = "/api/v1/guest/tabs/" + ana + "/closing";

        cliente.get(meu).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABERTA"))
                .andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(true))
                .andExpect(jsonPath("$.closure").value(nullValue()));
        new Browser(mvc).post(meu + "/without-value", "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));

        // CA-20: o cliente encerra sem autorização nem PSP; aberta, consolida e encerra de uma vez.
        relogio.advance(Duration.ofMinutes(5));
        Instant agora = relogio.instant();
        cliente.post(meu + "/without-value", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENCERRADA"))
                .andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.closure.kind").value("SEM_VALOR"))
                .andExpect(jsonPath("$.closure.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.canStart").value(false))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(false));
        cliente.post(meu + "/without-value", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"));

        Map<String, Object> comanda = admin().queryForMap(
                "SELECT closed_at, access_until FROM fastpay.tab WHERE id = ?", UUID.fromString(ana));
        assertThat(((Timestamp) comanda.get("closed_at")).toInstant()).isEqualTo(agora);
        assertThat(((Timestamp) comanda.get("access_until")).toInstant()).isEqualTo(agora.plus(Duration.ofDays(7)));
        assertThat(admin().queryForObject("""
                SELECT kind || ':' || (closed_by IS NULL) || ':' || (closed_by_grant IS NOT NULL) || ':' || b.total
                  FROM fastpay.tab_closure c JOIN fastpay.bill_version b ON b.id = c.bill_version_id
                 WHERE c.tab_id = ?
                """, String.class, UUID.fromString(ana))).isEqualTo("SEM_VALOR:true:true:0.00");
        assertThat(versoes(ana)).containsExactly("1:0.00");
        assertThat(auditoria(UUID.fromString(ana))).containsExactlyInAnyOrder("fechamento.iniciado", "comanda.encerrada");

        // A equipe vê como foi; nada é pagamento: o número fica livre e a comanda não recebe pedido.
        gabi.get(fechamento(ana)).andExpect(jsonPath("$.state").value("ENCERRADA"))
                .andExpect(jsonPath("$.closure.kind").value("SEM_VALOR"))
                .andExpect(jsonPath("$.closure.byConsumer").value(true))
                .andExpect(jsonPath("$.closure.closedByName").value(nullValue()))
                .andExpect(jsonPath("$.canAbandon").value(false));
        gabi.get("/api/v1/tabs").andExpect(jsonPath("$.length()").value(0));
        gabi.post("/api/v1/orders", pedido(ana, linha(chopp, 1, "9.90"))).andExpect(status().isConflict());
        cliente.get("/api/v1/guest/tabs/" + ana + "/bill").andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));

        // FR-11: o acesso avulso vale por 7 dias; depois disso, some.
        relogio.advance(Duration.ofDays(7));
        cliente.get(meu).andExpect(status().isNotFound());
    }

    @Test
    void descontoDeCemPorCentoComOuSemServicoEncerraSemValor() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        limite(vinculoGerente, "100");

        // Com serviço sobre o consumo após desconto: 100% zera consumo e serviço.
        String bia = comanda(gabi, "Bia");
        enviar(gabi, bia, linha(chopp, 2, "9.90"));
        entregarTudo(gabi, bia);
        gabi.get(fechamento(bia)).andExpect(jsonPath("$.total").value("21.78"))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(false));
        gabi.post(fechamento(bia) + "/without-value", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTA_COM_VALOR"));
        gui.put(conta(bia) + "/discount", conceder("PERCENTUAL", "100", "Cortesia da casa"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value("0.00"));
        gabi.get(fechamento(bia)).andExpect(jsonPath("$.canCloseWithoutValue").value(true))
                .andExpect(jsonPath("$.canAbandon").value(false));
        gabi.post(fechamento(bia) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_COM_CONSUMO"));
        // A equipe encerra pelo cliente sem celular, em nome próprio.
        gabi.post(fechamento(bia) + "/without-value", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENCERRADA"))
                .andExpect(jsonPath("$.closure.kind").value("SEM_VALOR"))
                .andExpect(jsonPath("$.closure.byConsumer").value(false))
                .andExpect(jsonPath("$.closure.closedByName").value("Gabi Garçom"));
        assertThat(versoes(bia)).containsExactly("1:0.00");

        // Em fechamento com valor: retirar o serviço não basta enquanto há consumo após desconto.
        String caio = comanda(gabi, "Caio");
        enviar(gabi, caio, linha(chopp, 1, "9.90"));
        entregarTudo(gabi, caio);
        gui.put(conta(caio) + "/discount", conceder("FIXO", "9.00", "Demora no pedido")).andExpect(status().isOk());
        gabi.post(fechamento(caio), total("0.99")).andExpect(status().isOk());
        gabi.put(conta(caio) + "/service", "{\"waived\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.90"));
        gabi.post(fechamento(caio) + "/without-value", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTA_COM_VALOR"));

        // Sem serviço na unidade: o desconto de 100% em fechamento já consolidado encerra a versão mais alta.
        novaConfiguracao("service_enabled = false");
        String duda = comanda(gabi, "Duda");
        enviar(gabi, duda, linha(chopp, 1, "9.90"));
        entregarTudo(gabi, duda);
        gui.put(conta(duda) + "/discount", conceder("PERCENTUAL", "100", "Cortesia da casa"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value("0.00"));
        Browser cliente = vincular(gabi, duda);
        cliente.post("/api/v1/guest/tabs/" + duda + "/closing", total("0.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EM_FECHAMENTO"))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(true));
        cliente.post("/api/v1/guest/tabs/" + duda + "/closing/without-value", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENCERRADA"));
        assertThat(versoes(duda)).containsExactly("1:0.00");
    }

    @Test
    void couvertEProblemaAbertoImpedemAteSeremResolvidos() throws Exception {
        novaConfiguracao("couvert_enabled = true, couvert_mode = 'AUTOMATICO', couvert_amount = 15.00, "
                + "service_enabled = false");
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String eva = comanda(gabi, "Eva");
        Browser cliente = vincular(gabi, eva);
        String meu = "/api/v1/guest/tabs/" + eva + "/closing";

        // Couvert automático é valor a pagar, mesmo sem pedido.
        cliente.get(meu).andExpect(jsonPath("$.total").value("15.00"))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(false));
        cliente.post(meu + "/without-value", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTA_COM_VALOR"));
        gabi.post(fechamento(eva) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTA_COM_VALOR"));

        // O cliente contesta o couvert: problema aberto impede mesmo depois do cancelamento (FR-24).
        String problema = JsonPath.read(cliente.post("/api/v1/guest/tabs/" + eva + "/issues",
                "{\"description\":\"Não ouvi música\",\"aboutCouvert\":true}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        String cobranca = JsonPath.read(gui.get(conta(eva)).andReturn().getResponse().getContentAsString(),
                "$.couvertDetail.chargeId");
        gui.post(conta(eva) + "/couvert/cancel", "{\"chargeId\":\"" + cobranca + "\",\"reasonKind\":\"ISENCAO\","
                + "\"reason\":\"Sem música hoje\"}").andExpect(status().isOk());
        cliente.get(meu).andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.blockers.openIssue").value(true))
                .andExpect(jsonPath("$.canCloseWithoutValue").value(false));
        cliente.post(meu + "/without-value", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_EM_ABERTO"));
        gabi.post(fechamento(eva) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_EM_ABERTO"));
        gabi.post("/api/v1/attendance/issues/" + problema + "/attend", "{}").andExpect(status().isOk());
        gui.post("/api/v1/attendance/issues/" + problema + "/conclude", "{\"version\":1,\"resolution\":\"Couvert "
                + "cancelado\",\"correction\":\"CORRECAO_REALIZADA\"}").andExpect(status().isOk());

        cliente.post(meu + "/without-value", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENCERRADA"));
        assertThat(versoes(eva)).containsExactly("1:0.00");

        // Depois do encerramento (FR-25, FR-11): novo problema suspende o prazo do acesso; a última resolução dá mais
        // 7 dias a partir dela.
        relogio.advance(Duration.ofDays(6));
        String depois = JsonPath.read(cliente.post("/api/v1/guest/tabs/" + eva + "/issues",
                "{\"description\":\"Cobraram o couvert no cartão\"}").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(), "$.id");
        assertThat(acessoAte(eva)).isNull();
        relogio.advance(Duration.ofDays(3));
        cliente.get(meu).andExpect(status().isOk());
        gui = login.inUnit(gerente, loja);
        gui.post("/api/v1/attendance/issues/" + depois + "/attend", "{}").andExpect(status().isOk());
        gui.post("/api/v1/attendance/issues/" + depois + "/conclude", "{\"version\":1,\"resolution\":\"Não houve "
                + "cobrança no cartão\",\"correction\":\"SEM_CORRECAO\"}").andExpect(status().isOk());
        assertThat(acessoAte(eva)).isEqualTo(relogio.instant().plus(Duration.ofDays(7)));
    }

    @Test
    void funcionarioEncerraAbandonoVazioComMotivoEAuditoria() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);

        // Sem pedido nenhum.
        String fabi = comanda(gabi, "Fabi");
        gabi.get(fechamento(fabi)).andExpect(jsonPath("$.canAbandon").value(true));
        gabi.post(fechamento(fabi) + "/abandon", motivo("ok")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        gabi.post(fechamento(fabi) + "/abandon", motivo("Cliente foi embora sem pedir")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENCERRADA"))
                .andExpect(jsonPath("$.closure.kind").value("ABANDONO"))
                .andExpect(jsonPath("$.closure.reason").value("Cliente foi embora sem pedir"))
                .andExpect(jsonPath("$.closure.closedByName").value("Gabi Garçom"));
        assertThat(admin().queryForObject("SELECT detail FROM fastpay.audit_event WHERE subject_id = ? AND action = "
                + "'comanda.encerrada'", String.class, UUID.fromString(fabi)))
                .isEqualTo("ABANDONO; pela equipe; Cliente foi embora sem pedir");
        gabi.post(fechamento(fabi) + "/abandon", motivo("Cliente foi embora sem pedir"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"));

        // Item pendente impede; com unidade ativa não é abandono; com tudo cancelado, é.
        String gil = comanda(gabi, "Gil");
        enviar(gabi, gil, linha(chopp, 1, "9.90"));
        gabi.post(fechamento(gil) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITENS_PENDENTES"));
        entregarTudo(gabi, gil);
        gabi.post(fechamento(gil) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_COM_CONSUMO"));
        String linhaPedido = admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ?
                """, String.class, UUID.fromString(gil));
        gui.post("/api/v1/tabs/" + gil + "/items/" + linhaPedido + "/cancel", "{\"quantity\":1,"
                + "\"expectedQuantity\":1,\"reason\":\"Lançado na comanda errada\"}").andExpect(status().isOk());
        gabi.get(fechamento(gil)).andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.canAbandon").value(true));
        gabi.post(fechamento(gil) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isOk())
                .andExpect(jsonPath("$.closure.kind").value("ABANDONO"));
        assertThat(versoes(gil)).containsExactly("1:0.00");

        // Produção sem permissão de operar comanda não encerra.
        UUID producao = fixtures.identity("Paulo Produção");
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        String hugo = comanda(gabi, "Hugo");
        login.inUnit(producao, loja).post(fechamento(hugo) + "/abandon", motivo("Cliente foi embora"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bancoSoAceitaEncerramentoRegistradoEComandaEncerradaSoMudaOPrazo() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String ivo = comanda(gabi, "Ivo");
        UUID id = UUID.fromString(ivo);

        // Pelo papel da aplicação, closed_at sem tab_closure é recusado (RLS restritiva).
        assertThatThrownBy(() -> tenants.inEstablishment(loja, () -> jdbc.update(
                "UPDATE tab SET closed_at = now() WHERE id = ?", id))).isNotNull();
        assertThat(admin().queryForObject("SELECT closed_at FROM fastpay.tab WHERE id = ?", Timestamp.class, id))
                .isNull();

        gabi.post(fechamento(ivo) + "/abandon", motivo("Cliente foi embora")).andExpect(status().isOk());
        // Encerrada é histórico: o gatilho só deixa mudar o prazo do acesso avulso.
        assertThatThrownBy(() -> tenants.inEstablishment(loja, () -> jdbc.update(
                "UPDATE tab SET closed_at = closed_at + interval '1 minute' WHERE id = ?", id))).isNotNull();
        assertThat(tenants.inEstablishment(loja, () -> jdbc.update(
                "UPDATE tab SET access_until = NULL WHERE id = ?", id))).isEqualTo(1);
    }

    // Apoio

    private Instant acessoAte(String comanda) {
        Timestamp t = admin().queryForObject("SELECT access_until FROM fastpay.tab WHERE id = ?", Timestamp.class,
                UUID.fromString(comanda));
        return t == null ? null : t.toInstant();
    }

    private void entregarTudo(Browser garcomDaVez, String comanda) throws Exception {
        rodarJobs();
        for (Map<String, Object> l : admin().queryForList("""
                SELECT p.id, p.version FROM fastpay.production_line p
                  JOIN fastpay.tab_allocation a ON a.order_line_id = p.order_line_id
                 WHERE a.tab_id = ? AND a.quantity > 0 AND p.status = 'PRONTO'
                """, UUID.fromString(comanda))) {
            garcomDaVez.post("/api/v1/production/lines/" + l.get("id") + "/deliver", "{\"version\":" + l.get("version")
                    + "}").andExpect(status().isOk());
        }
    }

    private List<String> versoes(String comanda) {
        return admin().queryForList("""
                SELECT number || ':' || total FROM fastpay.bill_version WHERE tab_id = ? ORDER BY created_at, number
                """, String.class, UUID.fromString(comanda));
    }

    private List<String> auditoria(UUID sujeito) {
        return admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE subject_id = ?
                   AND (action LIKE 'fechamento.%' OR action LIKE 'comanda.encerrada') ORDER BY occurred_at, action
                """, String.class, sujeito);
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

    private static String motivo(String texto) {
        return "{\"reason\":\"" + texto + "\"}";
    }

    private static String conceder(String tipo, String valor, String motivo) {
        return "{\"currentDiscountId\":null,\"kind\":\"" + tipo + "\",\"value\":\"" + valor + "\",\"reason\":\""
                + motivo + "\"}";
    }

    private void limite(UUID vinculo, String percentual) {
        admin().update("UPDATE fastpay.membership SET discount_limit_percent = ?::numeric WHERE id = ?", percentual,
                vinculo);
    }

    /** Nova versão da configuração (H14) a partir da atual, com as mudanças em SQL; vale para comandas novas. */
    private void novaConfiguracao(String mudancas) {
        UUID nova = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.unit_settings_version (id, establishment_id, number, service_enabled,
                       service_percent, service_base, service_includes_couvert, couvert_enabled, couvert_mode,
                       couvert_amount, discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                       created_by, created_at)
                SELECT ?, establishment_id, number + 1, service_enabled, service_percent, service_base,
                       service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                       discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode, ?, now()
                  FROM fastpay.unit_settings_version WHERE establishment_id = ?
                 ORDER BY number DESC LIMIT 1
                """, nova, dono, loja);
        admin().update("UPDATE fastpay.unit_settings_version SET " + mudancas + " WHERE id = ?", nova);
        relogio.advance(Duration.ofSeconds(1));
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

    private UUID produto(String nome, String preco) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.product (id, establishment_id, name, category, price, sector_id, created_by,
                                             created_at, updated_by, updated_at)
                VALUES (?, ?, ?, 'Geral', ?::numeric, NULL, ?, now(), ?, now())
                """, id, loja, nome, preco, dono, dono);
        return id;
    }

    private static String linha(UUID produto, int quantidade, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":null,\"expectedPrice\":\""
                + preco + "\"}";
    }
}
