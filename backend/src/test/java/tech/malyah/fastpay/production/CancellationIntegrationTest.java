package tech.malyah.fastpay.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Cancelamento de quantidade e ciência da produção pela API (H23, FR-20, FR-21, CA-09, D-06), com PostgreSQL real,
 * RLS, Spring Session e CSRF. Cobre a validação exigida no backlog: cancelamento antes, durante e depois do preparo
 * (e depois da entrega), ciência repetida, quantidade inválida, duas correções concorrentes, comanda encerrada
 * (pagamento existente) e, com a conta, o desconto em revisão (em {@code BillsIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class CancellationIntegrationTest extends PostgresIntegrationTest {

    private static final String FILA = "/api/v1/production/queue";
    private static final String ENTREGAS = "/api/v1/production/deliveries";
    private static final String AVISOS = "/api/v1/production/cancellations";

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
    UUID caipirinha;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Cancelamento");
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
        caipirinha = produto(loja, "Caipirinha", "24.00", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void cancelamentoAntesDuranteEDepoisDoPreparoComCienciaDaProducao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Ana");
        enviar(gabi, comanda, linha(batata, 3, "35.90"), linha(caipirinha, 2, "24.00"));
        String batataLinha = linhaDoPedido(comanda, "Batata frita");
        String caipLinha = linhaDoPedido(comanda, "Caipirinha");
        String conta = "/api/v1/tabs/" + comanda + "/bill";
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("155.70"));

        // Garçom sem delegação não cancela, nem por chamada direta (CA-04).
        gabi.post(cancelar(comanda, batataLinha), corpo(1, 3, "Cliente desistiu")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        // Antes do preparo: a fila só diminui, sem ciência; a versão sobe (contrato da entrega).
        String fila = paulo.get(FILA).andReturn().getResponse().getContentAsString();
        String batataProducao = idDe(fila, "Batata frita");
        gui.post(cancelar(comanda, batataLinha), corpo(1, 3, "Cliente desistiu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.originalQuantity").value(3))
                .andExpect(jsonPath("$.cancelledQuantity").value(1))
                .andExpect(jsonPath("$.remainingInTab").value(2))
                .andExpect(jsonPath("$.stage").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.requiresAck").value(false));
        paulo.get(FILA).andExpect(jsonPath("$.lines[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].originalQuantity").value(3))
                .andExpect(jsonPath("$.lines[0].cancelledQuantity").value(1))
                .andExpect(jsonPath("$.lines[0].destinations[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].version").value(1));
        paulo.get(AVISOS).andExpect(status().isOk()).andExpect(jsonPath("$.cancellations.length()").value(0));
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("119.80"));
        gabi.get("/api/v1/orders?tabId=" + comanda).andExpect(jsonPath("$[0].lines[0].quantity").value(3))
                .andExpect(jsonPath("$[0].lines[0].activeQuantity").value(2))
                .andExpect(jsonPath("$[0].lines[0].cancelledQuantity").value(1));

        // Em preparo: a conta já muda, a produção precisa dar ciência.
        String linhaProducao = "/api/v1/production/lines/" + batataProducao;
        paulo.post(linhaProducao + "/start", "{\"version\":1}").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(1));
        Instant cancelamento = relogio.instant();
        String emPreparo = JsonPath.read(gui.post(cancelar(comanda, batataLinha), corpo(1, 2, "Pediu só uma"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("EM_PREPARO"))
                .andExpect(jsonPath("$.requiresAck").value(true))
                .andReturn().getResponse().getContentAsString(), "$.cancellationId");
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("83.90"));
        paulo.get(AVISOS).andExpect(jsonPath("$.cancellations.length()").value(1))
                .andExpect(jsonPath("$.cancellations[0].id").value(emPreparo))
                .andExpect(jsonPath("$.cancellations[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.cancellations[0].stage").value("EM_PREPARO"))
                .andExpect(jsonPath("$.cancellations[0].quantity").value(1))
                .andExpect(jsonPath("$.cancellations[0].remaining").value(1))
                .andExpect(jsonPath("$.cancellations[0].reason").value("Pediu só uma"))
                .andExpect(jsonPath("$.cancellations[0].cancelledBy").value("Gui Gerente"))
                .andExpect(jsonPath("$.cancellations[0].cancelledAt").value(cancelamento.toString()))
                .andExpect(jsonPath("$.cancellations[0].tab.name").value("Ana"))
                .andExpect(jsonPath("$.cancellations[0].sectorName").value("Cozinha"));
        paulo.get(AVISOS + "?sectorId=" + UUID.randomUUID()).andExpect(jsonPath("$.cancellations.length()").value(0));
        gabi.get(AVISOS).andExpect(status().isForbidden());

        // Ciência: só a produção; repetir não muda nada.
        String ciencia = AVISOS + "/" + emPreparo + "/acknowledge";
        gabi.post(ciencia, "{}").andExpect(status().isForbidden());
        relogio.advance(Duration.ofSeconds(10));
        Instant ciente = relogio.instant();
        paulo.post(ciencia, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyAcknowledged").value(false))
                .andExpect(jsonPath("$.cancellation.acknowledgedBy").value("Paulo Produção"))
                .andExpect(jsonPath("$.cancellation.acknowledgedAt").value(ciente.toString()));
        relogio.advance(Duration.ofSeconds(10));
        paulo.post(ciencia, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyAcknowledged").value(true))
                .andExpect(jsonPath("$.cancellation.acknowledgedAt").value(ciente.toString()));
        paulo.get(AVISOS).andExpect(jsonPath("$.cancellations.length()").value(0));
        paulo.post(AVISOS + "/" + UUID.randomUUID() + "/acknowledge", "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANCELAMENTO_NAO_ENCONTRADO"));
        assertThat(auditoria(UUID.fromString(emPreparo))).containsExactly("item.cancelado",
                "item.cancelamento_ciente");

        // Pronto: a entrega com os destinos de antes é recusada; cancelar o resto encerra a linha.
        paulo.post(linhaProducao + "/ready", "{\"version\":3}").andExpect(status().isOk());
        Instant pronto = relogio.instant();
        assertThat(chamado(batataProducao)).isEqualTo("ITEM_PRONTO:null");
        relogio.advance(Duration.ofMinutes(1));
        String total = JsonPath.read(gui.post(cancelar(comanda, batataLinha), corpo(1, 1, "Saiu errado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.stage").value("PRONTO"))
                .andExpect(jsonPath("$.requiresAck").value(true))
                .andReturn().getResponse().getContentAsString(), "$.cancellationId");
        gabi.post(linhaProducao + "/deliver", "{\"version\":4}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gabi.post(linhaProducao + "/deliver", "{\"version\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));
        gabi.get(ENTREGAS).andExpect(jsonPath("$.lines.length()").value(0));
        assertThat(chamado(batataProducao)).isEqualTo("ITEM_PRONTO:CANCELADO");
        paulo.get(AVISOS).andExpect(jsonPath("$.cancellations[0].id").value(total))
                .andExpect(jsonPath("$.cancellations[0].status").value("CANCELADA"))
                .andExpect(jsonPath("$.cancellations[0].remaining").value(0));
        // O pronto cancelado conserva o preparo (FR-75) e o histórico tem cada passo.
        assertThat(admin().queryForObject("SELECT ready_at FROM fastpay.production_line WHERE id = ?",
                java.sql.Timestamp.class, UUID.fromString(batataProducao)).toInstant()).isEqualTo(pronto);
        assertThat(admin().queryForList("""
                SELECT action || ':' || from_status || '>' || to_status FROM fastpay.production_event
                 WHERE production_line_id = ? ORDER BY line_version
                """, String.class, UUID.fromString(batataProducao))).containsExactly(
                "CANCELADO:AGUARDANDO_PREPARO>AGUARDANDO_PREPARO", "INICIADO:AGUARDANDO_PREPARO>EM_PREPARO",
                "CANCELADO:EM_PREPARO>EM_PREPARO", "PRONTO:EM_PREPARO>PRONTO", "CANCELADO:PRONTO>CANCELADA");
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("48.00"))
                .andExpect(jsonPath("$.lines.length()").value(1));
        gui.post(cancelar(comanda, batataLinha), corpo(1, 0, "De novo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_JA_CANCELADO"));

        // Depois da entrega: só a correção financeira; a entrega fica no histórico, sem ciência.
        String caipProducao = idDe(paulo.get(FILA).andReturn().getResponse().getContentAsString(), "Caipirinha");
        String caip = "/api/v1/production/lines/" + caipProducao;
        paulo.post(caip + "/start", "{\"version\":0}").andExpect(status().isOk());
        paulo.post(caip + "/ready", "{\"version\":1}").andExpect(status().isOk());
        gabi.post(caip + "/deliver", "{\"version\":2}").andExpect(status().isOk());
        gui.post(cancelar(comanda, caipLinha), corpo(2, 2, "Bebida aguada")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ENTREGUE"))
                .andExpect(jsonPath("$.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.requiresAck").value(false));
        assertThat(admin().queryForObject("SELECT status || ':' || cancelled_quantity FROM fastpay.production_line "
                + "WHERE id = ?", String.class, UUID.fromString(caipProducao))).isEqualTo("ENTREGUE:2");
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("0.00"))
                .andExpect(jsonPath("$.lines.length()").value(0));
        // Nada é apagado: as linhas enviadas e as alocações (zeradas) continuam.
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.tab_allocation WHERE tab_id = ? AND quantity = 0
                """, Integer.class, UUID.fromString(comanda))).isEqualTo(2);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.item_cancellation WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda))).isEqualTo(4);
    }

    @Test
    void quantidadeInvalidaComandaEncerradaEDelegacao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser rita = login.inUnit(dono, loja);
        String comanda = comanda(gabi, "Bia");
        String outra = comanda(gabi, "Caio");
        enviar(gabi, comanda, linha(chopp, 2, "9.90"));
        String chopps = linhaDoPedido(comanda, "Chopp 300 ml");

        rita.post(cancelar(comanda, chopps), corpo(0, 2, "Cliente desistiu")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("quantity"));
        rita.post(cancelar(comanda, chopps), corpo(3, 2, "Cliente desistiu")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("quantity"))
                .andExpect(jsonPath("$.detail").value(containsString("no máximo 2")));
        rita.post(cancelar(comanda, chopps), corpo(1, 2, "ops")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        rita.post(cancelar(comanda, chopps), "{\"quantity\":1,\"reason\":\"Cliente desistiu\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("expectedQuantity"));
        // A tela mostrava outra quantidade: relê antes de corrigir.
        rita.post(cancelar(comanda, chopps), corpo(1, 3, "Cliente desistiu")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTIDADE_DESATUALIZADA"));
        rita.post(cancelar(outra, chopps), corpo(1, 2, "Cliente desistiu")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NAO_ENCONTRADO"));
        login.inUnit(gerenteDaOutra, outraLoja).post(cancelar(comanda, chopps), corpo(1, 2, "Cliente desistiu"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));

        // Ainda não recebido pela produção: espera o encaminhamento.
        enviarSemProcessar(gabi, outra, linha(chopp, 1, "9.90"));
        String naoRecebido = linhaDoPedido(outra, "Chopp 300 ml");
        rita.post(cancelar(outra, naoRecebido), corpo(1, 1, "Cliente desistiu")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_AGUARDANDO_PRODUCAO"));
        rodarJobs();

        // Garçom com a permissão delegada cancela; sem preparo não há ciência.
        delegar(vinculoGarcom, "CANCELAR_ITEM");
        gabi.post(cancelar(outra, naoRecebido), corpo(1, 1, "Cliente desistiu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.requiresAck").value(false));

        // Comanda encerrada (paga): não se cancela; o caminho é a devolução, sem apagar fatos.
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(comanda));
        rita.post(cancelar(comanda, chopps), corpo(1, 2, "Cliente desistiu")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"))
                .andExpect(jsonPath("$.detail").value(containsString("devolução")));
        assertThat(admin().queryForObject("SELECT sum(quantity) FROM fastpay.tab_allocation WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda))).isEqualTo(2);
    }

    @Test
    void duasCorrecoesConcorrentesCancelamUmaVez() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser rita = login.inUnit(dono, loja);
        String comanda = comanda(gabi, "Duda");
        enviar(gabi, comanda, linha(batata, 2, "35.90"));
        String linha = linhaDoPedido(comanda, "Batata frita");

        List<Integer> respostas = emParalelo(List.of(
                () -> gui.post(cancelar(comanda, linha), corpo(1, 2, "Cliente desistiu")).andReturn().getResponse()
                        .getStatus(),
                () -> rita.post(cancelar(comanda, linha), corpo(1, 2, "Cliente desistiu")).andReturn().getResponse()
                        .getStatus()));
        assertThat(respostas).containsExactlyInAnyOrder(200, 409);
        assertThat(admin().queryForObject("SELECT sum(quantity) FROM fastpay.tab_allocation WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda))).isEqualTo(1);
        assertThat(admin().queryForObject("""
                SELECT cancelled_quantity || ':' || version FROM fastpay.production_line p
                  JOIN fastpay.order_line l ON l.id = p.order_line_id WHERE l.id = ?
                """, String.class, UUID.fromString(linha))).isEqualTo("1:1");
    }

    @Test
    void cancelamentoResolveImpossibilidadeSemConcluirProblemaDoConsumidor() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Eva");
        enviar(gabi, comanda, linha(batata, 2, "35.90"));
        String linha = linhaDoPedido(comanda, "Batata frita");
        String producaoId = idDe(paulo.get(FILA).andReturn().getResponse().getContentAsString(), "Batata frita");
        String rota = "/api/v1/production/lines/" + producaoId;
        paulo.post(rota + "/impossible", "{\"version\":0,\"reason\":\"Acabou a batata\"}").andExpect(status().isOk());
        assertThat(chamado(producaoId)).isEqualTo("IMPOSSIBILIDADE:null");

        // O cliente reclama do item; o cancelamento corrige a conta, mas o problema continua com quem atende.
        Browser cliente = vincular(gabi, comanda);
        cliente.post("/api/v1/guest/tabs/" + comanda + "/issues",
                "{\"orderLineId\":\"" + linha + "\",\"description\":\"Batata não vem\"}")
                .andExpect(status().is2xxSuccessful());

        // Parcial: a sinalização continua.
        gui.post(cancelar(comanda, linha), corpo(1, 2, "Acabou a batata")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PREPARO"));
        paulo.get(FILA).andExpect(jsonPath("$.lines[0].impossible.reason").value("Acabou a batata"))
                .andExpect(jsonPath("$.lines[0].quantity").value(1));
        // Total: a linha termina, a sinalização sai e o aviso de impossibilidade conclui como cancelado.
        gui.post(cancelar(comanda, linha), corpo(1, 1, "Acabou a batata")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"));
        assertThat(chamado(producaoId)).isEqualTo("IMPOSSIBILIDADE:CANCELADO");
        paulo.get(FILA).andExpect(jsonPath("$.lines.length()").value(0));
        assertThat(admin().queryForObject("SELECT impossible_at FROM fastpay.production_line WHERE id = ?",
                java.sql.Timestamp.class, UUID.fromString(producaoId))).isNull();
        assertThat(admin().queryForObject("SELECT status FROM fastpay.consumer_issue WHERE tab_id = ?", String.class,
                UUID.fromString(comanda))).isEqualTo("ABERTO");

        // Lote (H19): linha cancelada falha sem impedir as demais.
        paulo.post("/api/v1/production/batch",
                "{\"action\":\"INICIAR\",\"lines\":[{\"id\":\"" + producaoId + "\",\"version\":3}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.lines[0].code").value("ETAPA_INVALIDA"))
                .andExpect(jsonPath("$.lines[0].message").value(containsString("etapa")));
        gabi.get("/api/v1/tabs/" + comanda + "/bill").andExpect(jsonPath("$.consumption").value("0.00"))
                .andExpect(jsonPath("$.discount").value(nullValue()));
    }

    // Apoio

    private static String cancelar(String comanda, String linha) {
        return "/api/v1/tabs/" + comanda + "/items/" + linha + "/cancel";
    }

    private static String corpo(int quantidade, int esperado, String motivo) {
        return "{\"quantity\":" + quantidade + ",\"expectedQuantity\":" + esperado + ",\"reason\":\"" + motivo
                + "\"}";
    }

    private String linhaDoPedido(String comanda, String produto) {
        return admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ? AND l.product_name = ?
                """, String.class, UUID.fromString(comanda), produto);
    }

    private String chamado(String linhaProducao) {
        return admin().queryForObject("""
                SELECT kind || ':' || coalesce(resolution, 'null') FROM fastpay.attendance_call
                 WHERE production_line_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, UUID.fromString(linhaProducao));
    }

    private List<String> auditoria(UUID sujeito) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? "
                + "ORDER BY occurred_at, action", String.class, sujeito);
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

    private Browser vincular(Browser equipe, String comanda) throws Exception {
        String link = JsonPath.read(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.link");
        Browser cliente = new Browser(mvc);
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + link.substring(link.indexOf('#') + 1)
                + "\"}").andExpect(status().isOk());
        return cliente;
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
        enviarSemProcessar(quem, comanda, linhas);
        rodarJobs();
    }

    private void enviarSemProcessar(Browser quem, String comanda, String... linhas) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        quem.post("/api/v1/orders", "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID()
                + "\",\"lines\":" + itens + "}").andExpect(status().isOk());
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
