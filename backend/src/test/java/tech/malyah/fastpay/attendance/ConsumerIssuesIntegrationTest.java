package tech.malyah.fastpay.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Problemas do consumidor pela API (H21, FR-24, FR-25, CA-14), com PostgreSQL real, RLS, Spring Session e CSRF. Cobre a
 * validação exigida no backlog: cliente de outra comanda, tratar sem permissão, cobrança bloqueada (o contrato para as
 * histórias de pagamento), correção sem concluir o chamado e caso pago encaminhado a gerente; e o aceite: item ou conta,
 * aberto → em atendimento → tratado, resolução obrigatória e marcar tratado sem alterar valores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class ConsumerIssuesIntegrationTest extends PostgresIntegrationTest {

    private static final String FILA = "/api/v1/attendance/calls";
    private static final String PROBLEMAS = "/api/v1/attendance/issues/";

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    ConsumerIssues problemas;

    @Autowired
    TenantTransactions tenants;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID ajudante;
    UUID producao;
    UUID garcomDaOutra;
    UUID vinculoGarcom;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Problema");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        ajudante = fixtures.identity("Hugo Ajudante");
        producao = fixtures.identity("Paulo Produção");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(ajudante, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
        UUID cozinha = setor(loja, "Cozinha");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void clienteInformaEquipeAtendeEConcluiComResolucao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Ana");
        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 2, "35.90"), linha(chopp, 3, "9.90")))
                .andExpect(status().isOk());
        rodarJobs();
        Browser cliente = vincular(gabi, comanda);
        String guest = "/api/v1/guest/tabs/" + comanda + "/issues";

        // O cliente vê os itens da própria comanda, sem valores, para apontar.
        String lista = cliente.get(guest).andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.issues.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String itemBatata = JsonPath.read(lista, "$.items[0].orderLineId");
        assertThat(cobrancaBloqueada(comanda)).isFalse();

        cliente.post(guest, "{\"orderLineId\":\"" + itemBatata + "\",\"description\":\"ruim\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("description"));
        String problemaItem = JsonPath.read(cliente.post(guest, "{\"orderLineId\":\"" + itemBatata
                + "\",\"description\":\"  Veio fria  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABERTO"))
                .andExpect(jsonPath("$.item.productName").value("Batata frita"))
                .andExpect(jsonPath("$.description").value("Veio fria"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Um em aberto por item: repetir não duplica.
        cliente.post(guest, "{\"orderLineId\":\"" + itemBatata + "\",\"description\":\"Ainda fria\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROBLEMA_JA_ABERTO"));
        relogio.advance(Duration.ofSeconds(5));
        String problemaConta = JsonPath.read(cliente.post(guest, "{\"description\":\"Não pedi 3 chopps\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.item").value(nullValue()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        cliente.post(guest, "{\"orderLineId\":null,\"description\":\"Outra coisa na conta\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROBLEMA_JA_ABERTO"));

        // CA-14: com problema aberto, nova cobrança fica bloqueada.
        assertThat(cobrancaBloqueada(comanda)).isTrue();

        // Na fila de atendimento, com a responsável da comanda como dona.
        gabi.get(FILA).andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andExpect(jsonPath("$.issues[0].id").value(problemaItem))
                .andExpect(jsonPath("$.issues[0].status").value("ABERTO"))
                .andExpect(jsonPath("$.issues[0].tab.name").value("Ana"))
                .andExpect(jsonPath("$.issues[0].item.productName").value("Batata frita"))
                .andExpect(jsonPath("$.issues[0].owner.name").value("Gabi Garçom"))
                .andExpect(jsonPath("$.issues[0].mine").value(true))
                .andExpect(jsonPath("$.issues[1].item").value(nullValue()));

        // Concluir exige atender antes; o garçom sem a permissão delegada não conclui.
        gui.post(PROBLEMAS + problemaItem + "/conclude", concluir(0, "Trocamos a porção", "CORRECAO_REALIZADA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROBLEMA_NAO_ATENDIDO"));
        gabi.post(PROBLEMAS + problemaItem + "/attend", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ATENDIMENTO"))
                .andExpect(jsonPath("$.attendedBy.name").value("Gabi Garçom"))
                .andExpect(jsonPath("$.version").value(1));
        cliente.get(guest).andExpect(jsonPath("$.issues[1].status").value("EM_ATENDIMENTO"));
        gabi.post(PROBLEMAS + problemaItem + "/conclude", concluir(1, "Trocamos a porção", "CORRECAO_REALIZADA"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        // Sem poder concluir, encaminha ao gerente.
        gabi.post(PROBLEMAS + problemaItem + "/forward", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.forwarded").value(true)).andExpect(jsonPath("$.version").value(2));

        // Resolução e declaração da correção obrigatórias.
        gui.post(PROBLEMAS + problemaItem + "/conclude", "{\"version\":2,\"correction\":\"SEM_CORRECAO\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("resolution"));
        gui.post(PROBLEMAS + problemaItem + "/conclude", "{\"version\":2,\"resolution\":\"Explicado ao cliente\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("correction"));
        gui.post(PROBLEMAS + problemaItem + "/conclude", concluir(1, "Explicado ao cliente", "SEM_CORRECAO"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        String valoresAntes = valores(comanda);
        gui.post(PROBLEMAS + problemaItem + "/conclude", concluir(2, "Porção trocada na hora", "CORRECAO_REALIZADA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TRATADO"));
        gui.post(PROBLEMAS + problemaItem + "/conclude", concluir(3, "De novo", "SEM_CORRECAO"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROBLEMA_TRATADO"));
        // Marcar tratado não altera valores.
        assertThat(valores(comanda)).isEqualTo(valoresAntes);

        // Ainda há o da conta: a cobrança continua bloqueada até ele ser tratado.
        assertThat(cobrancaBloqueada(comanda)).isTrue();
        cliente.get(guest).andExpect(jsonPath("$.issues[1].status").value("TRATADO"))
                .andExpect(jsonPath("$.issues[1].resolution").value("Porção trocada na hora"))
                .andExpect(jsonPath("$.issues[1].concludedAt").exists());
        gabi.get(FILA).andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].id").value(problemaConta));

        // Delegada ao garçom, a conclusão vale já na sessão aberta.
        delegar(vinculoGarcom, "CONCLUIR_PROBLEMA");
        gabi.post(PROBLEMAS + problemaConta + "/attend", "{}").andExpect(status().isOk());
        gabi.post(PROBLEMAS + problemaConta + "/conclude", concluir(1, "Eram 3 mesmo, conferido", "SEM_CORRECAO"))
                .andExpect(status().isOk());
        assertThat(cobrancaBloqueada(comanda)).isFalse();

        // Um novo problema sobre o mesmo item depois de tratado é aceito.
        cliente.post(guest, "{\"orderLineId\":\"" + itemBatata + "\",\"description\":\"Agora veio salgada\"}")
                .andExpect(status().isOk());

        assertThat(auditoria(problemaItem)).containsExactly("problema.informado", "problema.em_atendimento",
                "problema.encaminhado", "problema.tratado");
        assertThat(admin().queryForObject("SELECT correction FROM fastpay.consumer_issue WHERE id = ?", String.class,
                UUID.fromString(problemaItem))).isEqualTo("CORRECAO_REALIZADA");
    }

    @Test
    void correcaoNaComandaNaoConcluiOProblema() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Bia");
        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 1, "35.90"))).andExpect(status().isOk());
        rodarJobs();
        Browser cliente = vincular(gabi, comanda);
        String guest = "/api/v1/guest/tabs/" + comanda + "/issues";
        String item = JsonPath.read(cliente.get(guest).andReturn().getResponse().getContentAsString(),
                "$.items[0].orderLineId");
        String problema = JsonPath.read(cliente.post(guest, "{\"orderLineId\":\"" + item
                + "\",\"description\":\"Está demorando muito\"}").andReturn().getResponse().getContentAsString(),
                "$.id");
        gabi.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isOk());

        // A equipe age na comanda (troca a mesa; a produção prepara, marca pronto e entrega): nada conclui o problema.
        gabi.put("/api/v1/tabs/" + comanda + "/table", "{\"table\":\"8\",\"version\":0}").andExpect(status().isOk());
        String linha = JsonPath.read(paulo.get("/api/v1/production/queue").andReturn().getResponse()
                .getContentAsString(), "$.lines[0].id");
        paulo.post("/api/v1/production/lines/" + linha + "/start", "{\"version\":0}").andExpect(status().isOk());
        paulo.post("/api/v1/production/lines/" + linha + "/ready", "{\"version\":1}").andExpect(status().isOk());
        gabi.post("/api/v1/production/lines/" + linha + "/deliver", "{\"version\":2}").andExpect(status().isOk());

        gabi.get(FILA).andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].status").value("EM_ATENDIMENTO"));
        cliente.get(guest).andExpect(jsonPath("$.issues[0].status").value("EM_ATENDIMENTO"));
        assertThat(cobrancaBloqueada(comanda)).isTrue();
        // A produção não atende problema.
        paulo.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
    }

    @Test
    void clienteDeOutraComandaNaoVeNemInforma() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        String minha = comanda(gabi, "Ana");
        String alheia = comanda(gabi, "Caio");
        gabi.post("/api/v1/orders", envio(minha, linha(chopp, 1, "9.90"))).andExpect(status().isOk());
        gabi.post("/api/v1/orders", envio(alheia, linha(batata, 1, "35.90"))).andExpect(status().isOk());
        rodarJobs();
        Browser ana = vincular(gabi, minha);
        Browser caio = vincular(gabi, alheia);
        String itemDoCaio = JsonPath.read(caio.get("/api/v1/guest/tabs/" + alheia + "/issues").andReturn()
                .getResponse().getContentAsString(), "$.items[0].orderLineId");

        // Sem acesso para a comanda: a mesma resposta de comanda inexistente.
        ana.get("/api/v1/guest/tabs/" + alheia + "/issues").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        ana.post("/api/v1/guest/tabs/" + alheia + "/issues", "{\"description\":\"Cobraram errado\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        new Browser(mvc).post("/api/v1/guest/tabs/" + minha + "/issues", "{\"description\":\"Cobraram errado\"}")
                .andExpect(status().isNotFound());
        // Item de outra comanda na própria.
        ana.post("/api/v1/guest/tabs/" + minha + "/issues",
                "{\"orderLineId\":\"" + itemDoCaio + "\",\"description\":\"Não pedi isto\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ITEM_NAO_ENCONTRADO"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.consumer_issue WHERE tab_id IN (?, ?)",
                Integer.class, UUID.fromString(minha), UUID.fromString(alheia))).isZero();

        // Acesso revogado (recuperação pela equipe) deixa de valer também aqui.
        String problema = JsonPath.read(ana.post("/api/v1/guest/tabs/" + minha + "/issues",
                "{\"description\":\"Cobraram errado\"}").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.id");
        admin().update("UPDATE fastpay.guest_grant SET revoked_at = now(), revoke_reason = 'RECUPERACAO' "
                + "WHERE tab_id = ?", UUID.fromString(minha));
        ana.get("/api/v1/guest/tabs/" + minha + "/issues").andExpect(status().isNotFound());

        // Outra unidade não vê nem atende.
        otto.get(FILA).andExpect(jsonPath("$.issues.length()").value(0));
        otto.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROBLEMA_NAO_ENCONTRADO"));
    }

    @Test
    void centralAtendimentoExclusivoEReatribuicaoPeloGerente() throws Exception {
        mudarParaCentral();
        Browser gabi = login.inUnit(garcom, loja);
        Browser hugo = login.inUnit(ajudante, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Duda");
        Browser cliente = vincular(gabi, comanda);
        String problema = JsonPath.read(cliente.post("/api/v1/guest/tabs/" + comanda + "/issues",
                "{\"description\":\"Cobraram couvert\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        gabi.get(FILA).andExpect(jsonPath("$.issues[0].mode").value("CENTRAL"))
                .andExpect(jsonPath("$.issues[0].owner").value(nullValue()));

        // Duas pessoas ao mesmo tempo: uma fica com o problema, a outra é recusada com o nome de quem atende.
        List<Integer> codigos = emParalelo(List.of(
                () -> gabi.post(PROBLEMAS + problema + "/attend", "{}").andReturn().getResponse().getStatus(),
                () -> hugo.post(PROBLEMAS + problema + "/attend", "{}").andReturn().getResponse().getStatus()));
        assertThat(codigos).containsExactlyInAnyOrder(200, 409);
        String fila = gabi.get(FILA).andReturn().getResponse().getContentAsString();
        String dono = JsonPath.read(fila, "$.issues[0].owner.id");
        Browser perdedor = dono.equals(garcom.toString()) ? hugo : gabi;
        UUID outro = dono.equals(garcom.toString()) ? ajudante : garcom;
        perdedor.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_JA_ASSUMIDO"))
                .andExpect(jsonPath("$.holder").exists());

        // O gerente passa a quem perdeu.
        gabi.post(PROBLEMAS + problema + "/assign", atribuir(outro, 1)).andExpect(status().isForbidden());
        gui.get("/api/v1/attendance/issue-attendants").andExpect(status().isOk());
        gui.post(PROBLEMAS + problema + "/assign", atribuir(producao, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("assigneeId"));
        gui.post(PROBLEMAS + problema + "/assign", atribuir(outro, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.id").value(outro.toString()))
                .andExpect(jsonPath("$.status").value("EM_ATENDIMENTO"));
        perdedor.get(FILA).andExpect(jsonPath("$.issues[0].mine").value(true));
        assertThat(auditoria(problema)).containsExactly("problema.informado", "problema.em_atendimento",
                "problema.reatribuido");
    }

    @Test
    void garcomResponsavelNaoTemReatribuicaoDeProblema() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser hugo = login.inUnit(ajudante, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Eli");
        Browser cliente = vincular(gabi, comanda);
        String problema = JsonPath.read(cliente.post("/api/v1/guest/tabs/" + comanda + "/issues",
                "{\"description\":\"Cobraram couvert\"}").andReturn().getResponse().getContentAsString(), "$.id");
        // O ajudante começa (ajuda); a dona continua a responsável da comanda.
        hugo.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.attendedBy.name").value("Hugo Ajudante"))
                .andExpect(jsonPath("$.owner.name").value("Gabi Garçom"));
        gabi.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.attendedBy.name").value("Hugo Ajudante"));
        gui.post(PROBLEMAS + problema + "/assign", atribuir(ajudante, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEMA_COM_RESPONSAVEL"));
    }

    @Test
    void problemaDepoisDoPagamentoPreservaQuitacaoEVaiAoGerente() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Fabi");
        gabi.post("/api/v1/orders", envio(comanda, linha(chopp, 2, "9.90"))).andExpect(status().isOk());
        rodarJobs();
        Browser cliente = vincular(gabi, comanda);
        // Encerramento e quitação chegam com H28–H32: aqui a comanda é encerrada direto no banco, com o acesso de 7 dias.
        admin().update("""
                UPDATE fastpay.tab SET closed_at = now(), access_until = now() + interval '7 days' WHERE id = ?
                """, UUID.fromString(comanda));
        Map<String, Object> antes = admin().queryForMap("SELECT closed_at, access_until, version FROM fastpay.tab "
                + "WHERE id = ?", UUID.fromString(comanda));

        String guest = "/api/v1/guest/tabs/" + comanda + "/issues";
        cliente.get(guest).andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(true));
        String problema = JsonPath.read(cliente.post(guest, "{\"description\":\"Paguei um chopp a mais\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.afterClosure").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        gui.get(FILA).andExpect(jsonPath("$.issues[0].afterClosure").value(true))
                .andExpect(jsonPath("$.issues[0].forwarded").value(true));

        // Garçom com a conclusão delegada não fecha um caso pago: pode envolver devolução, decisão do gerente.
        delegar(vinculoGarcom, "CONCLUIR_PROBLEMA");
        gabi.post(PROBLEMAS + problema + "/attend", "{}").andExpect(status().isOk());
        gabi.post(PROBLEMAS + problema + "/conclude", concluir(1, "Vou devolver", "SEM_CORRECAO"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROBLEMA_EXIGE_GERENTE"));
        gui.post(PROBLEMAS + problema + "/conclude", concluir(1, "Conferido: eram 2 chopps", "SEM_CORRECAO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TRATADO"));

        // A quitação fica como estava.
        assertThat(admin().queryForMap("SELECT closed_at, access_until, version FROM fastpay.tab WHERE id = ?",
                UUID.fromString(comanda))).isEqualTo(antes);
    }

    @Test
    void runtimeNaoApagaNemReescreveProblemas() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Gil");
        Browser cliente = vincular(gabi, comanda);
        cliente.post("/api/v1/guest/tabs/" + comanda + "/issues", "{\"description\":\"Cobraram couvert\"}")
                .andExpect(status().isOk());

        JdbcTemplate runtime = new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl(), "fastpay_app", appPassword()));
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.consumer_issue", Integer.class)).isZero();
        assertThatThrownBy(() -> runtime.update("DELETE FROM fastpay.consumer_issue"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("UPDATE fastpay.consumer_issue SET description = 'outra coisa'"))
                .rootCause().hasMessageContaining("permission denied");
    }

    private boolean cobrancaBloqueada(String comanda) {
        return tenants.inEstablishment(loja, () -> problemas.blocksChargeInCurrentUnit(UUID.fromString(comanda)));
    }

    /** Linhas, preços e alocações da comanda: o que a conta usa. */
    private String valores(String comanda) {
        return admin().queryForList("""
                SELECT l.id, l.unit_price, l.quantity, a.quantity AS alocada
                  FROM fastpay.tab_allocation a JOIN fastpay.order_line l ON l.id = a.order_line_id
                 WHERE a.tab_id = ? ORDER BY l.id
                """, UUID.fromString(comanda)).toString();
    }

    private List<String> auditoria(String problema) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? "
                + "ORDER BY occurred_at, action", String.class, UUID.fromString(problema));
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

    private static String concluir(long versao, String resolucao, String correcao) {
        return "{\"version\":" + versao + ",\"resolution\":\"" + resolucao + "\",\"correction\":\"" + correcao
                + "\"}";
    }

    private static String atribuir(UUID pessoa, long versao) {
        return "{\"assigneeId\":\"" + pessoa + "\",\"version\":" + versao + "}";
    }

    /** Nova versão da configuração com a modalidade central (H14): só vale para as comandas abertas depois. */
    private void mudarParaCentral() {
        admin().update("""
                INSERT INTO fastpay.unit_settings_version (id, establishment_id, number, service_enabled,
                       service_percent, service_base, service_includes_couvert, couvert_enabled, couvert_mode,
                       couvert_amount, discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                       created_by, created_at)
                SELECT gen_random_uuid(), establishment_id, number + 1, service_enabled, service_percent, service_base,
                       service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                       discount_fixed_allowed, discount_percent_allowed, 'CENTRAL', exit_mode, ?, now()
                  FROM fastpay.unit_settings_version WHERE establishment_id = ?
                 ORDER BY number DESC LIMIT 1
                """, dono, loja);
    }

    private void rodarJobs() {
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
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

    private static String linha(UUID produto, int quantidade, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":null,\"expectedPrice\":\""
                + preco + "\"}";
    }

    private static String envio(String comanda, String... linhas) {
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        return "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID() + "\",\"lines\":" + itens
                + "}";
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
