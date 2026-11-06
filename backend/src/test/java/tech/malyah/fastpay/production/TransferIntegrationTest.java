package tech.malyah.fastpay.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
 * Transferência de unidades entre comandas pela API (H25, FR-17, RN-01, CA-10, D-05), com PostgreSQL real, RLS,
 * Spring Session e CSRF. Cobre a validação exigida no backlog: divisão de 3 unidades em 2 + 1 com produção única,
 * conflito de destinos na entrega, linha entregue, tentativa fora da unidade, lock em ordem (transferências cruzadas
 * simultâneas) e rollback entre contas; e também problema do item e da conta, desconto avaliado nas duas contas e os
 * avisos de atendimento seguindo as unidades.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class TransferIntegrationTest extends PostgresIntegrationTest {

    private static final String FILA = "/api/v1/production/queue";
    private static final String ENTREGAS = "/api/v1/production/deliveries";

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
    UUID vinculoGerente;
    UUID cozinha;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Transferência");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        gerenteDaOutra = fixtures.identity("Olga Outra");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(gerenteDaOutra, outraLoja, MembershipRole.GERENTE);
        cozinha = setor(loja, "Cozinha");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void divideTresUnidadesEmDuasMaisUmaSemDuplicarProducao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser paulo = login.inUnit(producao, loja);
        String ana = comanda(gabi, "Ana");
        String bia = comanda(gabi, "Bia");
        enviar(gabi, ana, linha(batata, 3, "35.90", "sem sal"));
        String linha = linhaDoPedido(ana, "Batata frita");
        String producaoId = idDe(paulo.get(FILA).andReturn().getResponse().getContentAsString(), "Batata frita");
        // O preço do catálogo muda depois do envio: a transferência leva o preço do envio (FR-16).
        admin().update("UPDATE fastpay.product SET price = 40.00 WHERE id = ?", batata);

        // Garçom sem delegação não transfere, nem por chamada direta (CA-04).
        gabi.post(transferir(ana, linha), corpo(bia, 1, 3, "Dividiu com a amiga")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        // 3 = 2 + 1: uma linha de produção só, com dois destinos; a etapa fica, a versão sobe.
        String transferencia = JsonPath.read(gui.post(transferir(ana, linha), corpo(bia, 1, 3, "Dividiu com a amiga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.remainingInFrom").value(2))
                .andExpect(jsonPath("$.quantityInTo").value(1))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString(), "$.transferId");
        paulo.get(FILA).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].note").value("sem sal"))
                .andExpect(jsonPath("$.lines[0].status").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.lines[0].destinations.length()").value(2))
                .andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Ana')].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Bia')].quantity").value(1))
                .andExpect(jsonPath("$.lines[0].version").value(1));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.production_line WHERE order_line_id = ?",
                Integer.class, UUID.fromString(linha))).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.order_line WHERE product_id = ?",
                Integer.class, batata)).isEqualTo(1);

        // As duas contas mudam no mesmo commit, com o preço do envio.
        gabi.get(conta(ana)).andExpect(jsonPath("$.consumption").value("71.80"));
        gabi.get(conta(bia)).andExpect(jsonPath("$.consumption").value("35.90"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("35.90"));
        // A origem mostra o que ficou e o que saiu; o destino mostra o recebido e de onde veio.
        gabi.get("/api/v1/orders?tabId=" + ana).andExpect(jsonPath("$[0].lines[0].quantity").value(3))
                .andExpect(jsonPath("$[0].lines[0].activeQuantity").value(3))
                .andExpect(jsonPath("$[0].lines[0].inTabQuantity").value(2))
                .andExpect(jsonPath("$[0].lines[0].transferredQuantity").value(1))
                .andExpect(jsonPath("$[0].lines[0].cancelledQuantity").value(0));
        gabi.get("/api/v1/orders/received?tabId=" + bia).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(linha))
                .andExpect(jsonPath("$[0].quantity").value(1))
                .andExpect(jsonPath("$[0].unitPrice").value("35.90"))
                .andExpect(jsonPath("$[0].lineTotal").value("35.90"))
                .andExpect(jsonPath("$[0].origin.name").value("Ana"));
        gabi.get("/api/v1/orders/received?tabId=" + ana).andExpect(jsonPath("$.length()").value(0));
        assertThat(auditoria(UUID.fromString(transferencia))).containsExactly("item.transferido");
        assertThat(admin().queryForObject("""
                SELECT from_tab_id::text || '>' || to_tab_id::text || ':' || quantity || ':' || remaining_in_from
                       || ':' || stage || ':' || reason FROM fastpay.item_transfer WHERE id = ?
                """, String.class, UUID.fromString(transferencia)))
                .isEqualTo(ana + ">" + bia + ":1:2:AGUARDANDO_PREPARO:Dividiu com a amiga");

        // Conflito de destinos na entrega: quem viu os destinos de antes é recusado e relê.
        String rota = "/api/v1/production/lines/" + producaoId;
        paulo.post(rota + "/start", "{\"version\":1}").andExpect(status().isOk());
        paulo.post(rota + "/ready", "{\"version\":2}").andExpect(status().isOk());
        assertThat(chamados(producaoId)).containsExactlyInAnyOrder("Ana:ITEM_PRONTO:null", "Bia:ITEM_PRONTO:null");
        gui.post(transferir(ana, linha), corpo(bia, 1, 2, "Trocou de lugar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRONTO"))
                .andExpect(jsonPath("$.version").value(4));
        gabi.post(rota + "/deliver", "{\"version\":3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gabi.get(ENTREGAS).andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Ana')].quantity").value(1))
                .andExpect(jsonPath("$.lines[0].destinations[?(@.name == 'Bia')].quantity").value(2));

        // A última unidade sai de Ana: o aviso dela termina como transferido; o de Bia continua.
        gui.post(transferir(ana, linha), corpo(bia, 1, 1, "Trocou de lugar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingInFrom").value(0))
                .andExpect(jsonPath("$.quantityInTo").value(3));
        assertThat(chamados(producaoId)).containsExactlyInAnyOrder("Ana:ITEM_PRONTO:TRANSFERIDO",
                "Bia:ITEM_PRONTO:null");
        gabi.get(conta(ana)).andExpect(jsonPath("$.consumption").value("0.00"))
                .andExpect(jsonPath("$.lines.length()").value(0));
        gui.post(transferir(ana, linha), corpo(bia, 1, 0, "De novo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_SEM_UNIDADES"));
        gabi.post(rota + "/deliver", "{\"version\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.destinations.length()").value(1));
        assertThat(chamados(producaoId)).containsExactlyInAnyOrder("Ana:ITEM_PRONTO:TRANSFERIDO",
                "Bia:ITEM_PRONTO:ENTREGUE");
        Instant entregue = admin().queryForObject("SELECT delivered_at FROM fastpay.production_line WHERE id = ?",
                java.sql.Timestamp.class, UUID.fromString(producaoId)).toInstant();

        // Linha entregue: a correção é só de consumo; a entrega e os instantes ficam, sem aviso novo.
        relogio.advance(Duration.ofMinutes(2));
        gui.post(transferir(bia, linha), corpo(ana, 1, 3, "Era da Ana")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTREGUE"));
        assertThat(admin().queryForObject("SELECT status FROM fastpay.production_line WHERE id = ?", String.class,
                UUID.fromString(producaoId))).isEqualTo("ENTREGUE");
        assertThat(admin().queryForObject("SELECT delivered_at FROM fastpay.production_line WHERE id = ?",
                java.sql.Timestamp.class, UUID.fromString(producaoId)).toInstant()).isEqualTo(entregue);
        assertThat(chamados(producaoId)).hasSize(2);
        gabi.get(conta(ana)).andExpect(jsonPath("$.consumption").value("35.90"));
        gabi.get(conta(bia)).andExpect(jsonPath("$.consumption").value("71.80"));
        // Uma alocação por linha e comanda: a de Ana (zerada) voltou a ter a unidade; a soma é sempre 3.
        assertThat(admin().queryForList("""
                SELECT t.display_name || ':' || a.quantity FROM fastpay.tab_allocation a
                  JOIN fastpay.tab t ON t.id = a.tab_id WHERE a.order_line_id = ? ORDER BY t.display_name
                """, String.class, UUID.fromString(linha))).containsExactly("Ana:1", "Bia:2");
        assertThat(admin().queryForList("""
                SELECT action || ':' || from_status || '>' || to_status FROM fastpay.production_event
                 WHERE production_line_id = ? ORDER BY line_version
                """, String.class, UUID.fromString(producaoId))).containsExactly(
                "TRANSFERIDO:AGUARDANDO_PREPARO>AGUARDANDO_PREPARO", "INICIADO:AGUARDANDO_PREPARO>EM_PREPARO",
                "PRONTO:EM_PREPARO>PRONTO", "TRANSFERIDO:PRONTO>PRONTO", "TRANSFERIDO:PRONTO>PRONTO",
                "ENTREGUE:PRONTO>ENTREGUE", "TRANSFERIDO:ENTREGUE>ENTREGUE");
    }

    @Test
    void validacoesForaDaUnidadeComandasEncerradasEDelegacao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser rita = login.inUnit(dono, loja);
        Browser olga = login.inUnit(gerenteDaOutra, outraLoja);
        String cris = comanda(gabi, "Cris");
        String davi = comanda(gabi, "Davi");
        String daOutra = comanda(olga, "Fora");
        enviar(gabi, cris, linha(chopp, 2, "9.90", null));
        String chopps = linhaDoPedido(cris, "Chopp 300 ml");

        rita.post(transferir(cris, chopps), corpo(davi, 0, 2, "Mudou de mesa")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("quantity"));
        rita.post(transferir(cris, chopps), corpo(davi, 3, 2, "Mudou de mesa")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("quantity"))
                .andExpect(jsonPath("$.detail").value(containsString("no máximo 2")));
        rita.post(transferir(cris, chopps), corpo(davi, 1, 2, "ops")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        rita.post(transferir(cris, chopps), "{\"quantity\":1,\"expectedQuantity\":2,\"reason\":\"Mudou de mesa\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("toTabId"));
        rita.post(transferir(cris, chopps), corpo(cris, 1, 2, "Mudou de mesa")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("toTabId"));
        rita.post(transferir(cris, chopps), "{\"toTabId\":\"" + davi + "\",\"quantity\":1,\"reason\":\"Mudou de "
                + "mesa\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("expectedQuantity"));
        rita.post(transferir(cris, chopps), corpo(davi, 1, 3, "Mudou de mesa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTIDADE_DESATUALIZADA"));
        rita.post(transferir(davi, chopps), corpo(cris, 1, 2, "Mudou de mesa")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NAO_ENCONTRADO"));

        // Fora da unidade: o destino de outra loja não existe aqui (RLS); nem a origem para quem é de outra loja.
        rita.post(transferir(cris, chopps), corpo(daOutra, 1, 2, "Mudou de mesa")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DESTINO_NAO_ENCONTRADO"));
        rita.post(transferir(cris, chopps), corpo(UUID.randomUUID().toString(), 1, 2, "Mudou de mesa"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DESTINO_NAO_ENCONTRADO"));
        olga.post(transferir(cris, chopps), corpo(daOutra, 1, 2, "Mudou de mesa")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        olga.get("/api/v1/orders/received?tabId=" + cris).andExpect(status().isNotFound());

        // Ainda não recebido pela produção: espera o encaminhamento.
        enviarSemProcessar(gabi, davi, linha(chopp, 1, "9.90", null));
        String naoRecebido = linhaDoPedido(davi, "Chopp 300 ml");
        rita.post(transferir(davi, naoRecebido), corpo(cris, 1, 1, "Mudou de mesa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_AGUARDANDO_PRODUCAO"));
        rodarJobs();

        // Garçom com a permissão delegada transfere.
        delegar(vinculoGarcom, "TRANSFERIR_UNIDADES");
        gabi.post(transferir(davi, naoRecebido), corpo(cris, 1, 1, "Mudou de mesa")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRONTO"));

        // Tudo cancelado: não há o que transferir.
        rita.post("/api/v1/tabs/" + cris + "/items/" + chopps + "/cancel",
                "{\"quantity\":2,\"expectedQuantity\":2,\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk());
        rita.post(transferir(cris, chopps), corpo(davi, 1, 0, "Mudou de mesa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_SEM_UNIDADES"));

        // Comandas encerradas (pagas) não recebem nem cedem unidades.
        String eva = comanda(gabi, "Eva");
        enviar(gabi, eva, linha(chopp, 2, "9.90", null));
        String daEva = linhaDoPedido(eva, "Chopp 300 ml");
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(davi));
        rita.post(transferir(eva, daEva), corpo(davi, 1, 2, "Mudou de mesa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESTINO_ENCERRADO"));
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(eva));
        rita.post(transferir(eva, daEva), corpo(cris, 1, 2, "Mudou de mesa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"));
        assertThat(admin().queryForObject("SELECT sum(quantity) FROM fastpay.tab_allocation WHERE tab_id = ?",
                Integer.class, UUID.fromString(eva))).isEqualTo(2);
    }

    @Test
    void problemaDoItemEDaContaBloqueiamEDescontoEAvaliadoNasDuasContas() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        limite(vinculoGerente, "20");
        String ana = comanda(gabi, "Ana");
        String bia = comanda(gabi, "Bia");
        String caio = comanda(gabi, "Caio");
        enviar(gabi, ana, linha(batata, 2, "35.90", null));
        enviar(gabi, ana, linha(chopp, 3, "9.90", null));
        enviar(gabi, bia, linha(chopp, 1, "9.90", null));
        String batatas = linhaDoPedido(ana, "Batata frita");
        String chopps = linhaDoPedido(ana, "Chopp 300 ml");

        // Problema do item bloqueia aquele item, não os outros da comanda.
        Browser clienteAna = vincular(gabi, ana);
        clienteAna.post("/api/v1/guest/tabs/" + ana + "/issues",
                "{\"orderLineId\":\"" + batatas + "\",\"description\":\"Batata fria\"}")
                .andExpect(status().is2xxSuccessful());
        gui.post(transferir(ana, batatas), corpo(bia, 1, 2, "Era da Bia")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_NO_ITEM"));

        // Problema da conta do destino bloqueia entradas nele; o da origem, saídas dela.
        Browser clienteCaio = vincular(gabi, caio);
        clienteCaio.post("/api/v1/guest/tabs/" + caio + "/issues",
                "{\"orderLineId\":null,\"description\":\"Conta errada\"}").andExpect(status().is2xxSuccessful());
        gui.post(transferir(ana, chopps), corpo(caio, 1, 3, "Era do Caio")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_NA_CONTA_DESTINO"));
        String duda = comanda(gabi, "Duda");
        enviar(gabi, duda, linha(chopp, 1, "9.90", null));
        String dudaChopp = linhaDoPedido(duda, "Chopp 300 ml");
        Browser clienteDuda = vincular(gabi, duda);
        clienteDuda.post("/api/v1/guest/tabs/" + duda + "/issues",
                "{\"orderLineId\":null,\"description\":\"Cobraram a mais\"}").andExpect(status().is2xxSuccessful());
        gui.post(transferir(duda, dudaChopp), corpo(ana, 1, 1, "Era da Ana")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_NA_CONTA"));

        // Desconto nas duas contas. Ana: C = 101,50 e 20,30 = 20%, no limite de quem concedeu.
        gui.put(conta(ana) + "/discount", conceder("FIXO", "20.30", "Demora no pedido")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewPending").value(false));
        // Bia: C = 9,90 e 1,98 = 20%.
        gui.put(conta(bia) + "/discount", conceder("FIXO", "1.98", "Demora no pedido")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewPending").value(false));

        // 2 chopps de Ana para Bia: Ana cai para 81,70 (24,85%, acima da autoridade: revisão, cobrança bloqueada);
        // Bia sobe para 29,70 (6,67%, sem revisão). A revisão não impede a transferência.
        gui.post(transferir(ana, chopps), corpo(bia, 2, 3, "Dividiram a conta")).andExpect(status().isOk());
        gabi.get(conta(ana)).andExpect(jsonPath("$.consumption").value("81.70"))
                .andExpect(jsonPath("$.discount.amount").value("20.30"))
                .andExpect(jsonPath("$.discount.effectivePercent").value("24.85"))
                .andExpect(jsonPath("$.discount.needsReview").value(true))
                .andExpect(jsonPath("$.reviewPending").value(true));
        gabi.get(conta(bia)).andExpect(jsonPath("$.consumption").value("29.70"))
                .andExpect(jsonPath("$.discount.amount").value("1.98"))
                .andExpect(jsonPath("$.discount.needsReview").value(false))
                .andExpect(jsonPath("$.reviewPending").value(false));
        // Correção legítima com a conta em revisão continua possível.
        gui.post(transferir(ana, chopps), corpo(bia, 1, 1, "Dividiram a conta")).andExpect(status().isOk());
        gabi.get(conta(bia)).andExpect(jsonPath("$.consumption").value("39.60"));
        // O problema do item continua com quem atende: transferir não conclui nada.
        assertThat(admin().queryForObject("SELECT status FROM fastpay.consumer_issue WHERE order_line_id = ?",
                String.class, UUID.fromString(batatas))).isEqualTo("ABERTO");
    }

    @Test
    void transferenciasCruzadasSimultaneasTravamEmOrdemSemImpasse() throws Exception {
        Browser gui = login.inUnit(gerente, loja);
        Browser rita = login.inUnit(dono, loja);
        String ana = comanda(gui, "Ana");
        String bia = comanda(gui, "Bia");
        enviar(gui, ana, linha(chopp, 10, "9.90", null));
        enviar(gui, bia, linha(batata, 10, "35.90", null));
        String chopps = linhaDoPedido(ana, "Chopp 300 ml");
        String batatas = linhaDoPedido(bia, "Batata frita");

        // A→B e B→A ao mesmo tempo, várias vezes: as duas travam Ana e Bia na mesma ordem e passam.
        for (int i = 0; i < 5; i++) {
            int restam = 10 - i;
            List<Integer> respostas = emParalelo(List.of(
                    () -> gui.post(transferir(ana, chopps), corpo(bia, 1, restam, "Dividiram a conta")).andReturn()
                            .getResponse().getStatus(),
                    () -> rita.post(transferir(bia, batatas), corpo(ana, 1, restam, "Dividiram a conta")).andReturn()
                            .getResponse().getStatus()));
            assertThat(respostas).containsExactly(200, 200);
        }
        assertThat(alocacoes(chopps)).containsExactly("Ana:5", "Bia:5");
        assertThat(alocacoes(batatas)).containsExactly("Ana:5", "Bia:5");

        // A mesma transferência duas vezes ao mesmo tempo: uma passa, a outra vê a quantidade mudada.
        List<Integer> respostas = emParalelo(List.of(
                () -> gui.post(transferir(ana, chopps), corpo(bia, 2, 5, "Dividiram a conta")).andReturn()
                        .getResponse().getStatus(),
                () -> rita.post(transferir(ana, chopps), corpo(bia, 2, 5, "Dividiram a conta")).andReturn()
                        .getResponse().getStatus()));
        assertThat(respostas).containsExactlyInAnyOrder(200, 409);
        assertThat(alocacoes(chopps)).containsExactly("Ana:3", "Bia:7");
        assertThat(admin().queryForObject("""
                SELECT version FROM fastpay.production_line WHERE order_line_id = ?
                """, Long.class, UUID.fromString(chopps))).isEqualTo(6L);
    }

    @Test
    void falhaNoMeioDesfazAsDuasContas() throws Exception {
        Browser gui = login.inUnit(gerente, loja);
        String ana = comanda(gui, "Ana");
        String bia = comanda(gui, "Bia");
        enviar(gui, ana, linha(chopp, 3, "9.90", null));
        String chopps = linhaDoPedido(ana, "Chopp 300 ml");

        // O último passo (o fato da transferência) falha depois de mexer nas alocações e na linha de produção.
        admin().execute("""
                CREATE FUNCTION fastpay.falha_simulada() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'falha simulada'; END $$
                """);
        admin().execute("""
                CREATE TRIGGER falha_simulada BEFORE INSERT ON fastpay.item_transfer
                   FOR EACH ROW EXECUTE FUNCTION fastpay.falha_simulada()
                """);
        try {
            int codigo;
            try {
                codigo = gui.post(transferir(ana, chopps), corpo(bia, 2, 3, "Dividiram a conta")).andReturn()
                        .getResponse().getStatus();
            } catch (Exception falha) {
                codigo = 500;
            }
            assertThat(codigo).isEqualTo(500);
        } finally {
            admin().execute("DROP TRIGGER falha_simulada ON fastpay.item_transfer");
            admin().execute("DROP FUNCTION fastpay.falha_simulada()");
        }
        assertThat(alocacoes(chopps)).containsExactly("Ana:3");
        assertThat(admin().queryForObject("SELECT version FROM fastpay.production_line WHERE order_line_id = ?",
                Long.class, UUID.fromString(chopps))).isEqualTo(0L);
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.production_event e JOIN fastpay.production_line p
                    ON p.id = e.production_line_id WHERE p.order_line_id = ?
                """, Integer.class, UUID.fromString(chopps))).isEqualTo(0);
        gui.get(conta(ana)).andExpect(jsonPath("$.consumption").value("29.70"));
        gui.get(conta(bia)).andExpect(jsonPath("$.consumption").value("0.00"));

        // Sem a falha, a mesma transferência passa.
        gui.post(transferir(ana, chopps), corpo(bia, 2, 3, "Dividiram a conta")).andExpect(status().isOk());
        assertThat(alocacoes(chopps)).containsExactly("Ana:1", "Bia:2");
    }

    // Apoio

    private static String transferir(String comanda, String linha) {
        return "/api/v1/tabs/" + comanda + "/items/" + linha + "/transfer";
    }

    private static String conta(String comanda) {
        return "/api/v1/tabs/" + comanda + "/bill";
    }

    private static String corpo(String destino, int quantidade, int esperado, String motivo) {
        return "{\"toTabId\":\"" + destino + "\",\"quantity\":" + quantidade + ",\"expectedQuantity\":" + esperado
                + ",\"reason\":\"" + motivo + "\"}";
    }

    private static String conceder(String tipo, String valor, String motivo) {
        return "{\"currentDiscountId\":null,\"kind\":\"" + tipo + "\",\"value\":\"" + valor + "\",\"reason\":\""
                + motivo + "\"}";
    }

    private String linhaDoPedido(String comanda, String produto) {
        return admin().queryForObject("""
                SELECT l.id::text FROM fastpay.order_line l JOIN fastpay.customer_order o ON o.id = l.order_id
                 WHERE o.tab_id = ? AND l.product_name = ?
                """, String.class, UUID.fromString(comanda), produto);
    }

    private List<String> alocacoes(String linha) {
        return admin().queryForList("""
                SELECT t.display_name || ':' || sum(a.quantity) FROM fastpay.tab_allocation a
                  JOIN fastpay.tab t ON t.id = a.tab_id WHERE a.order_line_id = ?
                 GROUP BY t.display_name ORDER BY t.display_name
                """, String.class, UUID.fromString(linha));
    }

    private List<String> chamados(String linhaProducao) {
        return admin().queryForList("""
                SELECT t.display_name || ':' || c.kind || ':' || coalesce(c.resolution, 'null')
                  FROM fastpay.attendance_call c JOIN fastpay.tab t ON t.id = c.tab_id
                 WHERE c.production_line_id = ?
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

    private static String linha(UUID produto, int quantidade, String preco, String observacao) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":"
                + (observacao == null ? "null" : "\"" + observacao + "\"") + ",\"expectedPrice\":\"" + preco + "\"}";
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
