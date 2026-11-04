package tech.malyah.fastpay.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
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
 * Atendimento responsável ou central pela API (H20, FR-23, FR-26, FR-27, FR-68), com PostgreSQL real, RLS, Spring
 * Session e CSRF. Cobre a validação exigida no backlog: dois funcionários assumem ao mesmo tempo, ajuda permitida,
 * reatribuição e troca de modalidade sem migrar chamados antigos; e o aceite: modalidade capturada na abertura,
 * responsável inicial, fila de prontos e impossibilidades, entrega conclui o aviso e ler não conclui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class AttendanceIntegrationTest extends PostgresIntegrationTest {

    private static final String CHAMADOS = "/api/v1/attendance/calls";

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
    UUID ajudante;
    UUID producao;
    UUID garcomDaOutra;
    UUID cozinha;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Atendimento");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        ajudante = fixtures.identity("Hugo Ajudante");
        producao = fixtures.identity("Paulo Produção");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(ajudante, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
        cozinha = setor(loja, "Cozinha");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void garcomResponsavelRecebeOsAvisosEOutrosAjudam() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser hugo = login.inUnit(ajudante, loja);
        Browser paulo = login.inUnit(producao, loja);

        // Modalidade padrão (garçom responsável), capturada na abertura: quem abre assume.
        String comanda = JsonPath.read(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\",\"table\":\"5\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceMode").value("GARCOM_RESPONSAVEL"))
                .andExpect(jsonPath("$.responsibleId").value(garcom.toString()))
                .andExpect(jsonPath("$.responsibleName").value("Gabi Garçom"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        gabi.get(CHAMADOS).andExpect(status().isOk()).andExpect(jsonPath("$.calls.length()").value(0));

        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 2, null, "35.90"),
                linha(chopp, 3, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();

        // O chopp (sem preparo) nasceu pronto: aviso para a responsável; o ajudante vê, mas não é dele.
        String choppCall = JsonPath.read(gabi.get(CHAMADOS).andExpect(status().isOk())
                .andExpect(jsonPath("$.calls.length()").value(1))
                .andExpect(jsonPath("$.calls[0].kind").value("ITEM_PRONTO"))
                .andExpect(jsonPath("$.calls[0].mode").value("GARCOM_RESPONSAVEL"))
                .andExpect(jsonPath("$.calls[0].tab.number").value(1))
                .andExpect(jsonPath("$.calls[0].tab.name").value("Ana"))
                .andExpect(jsonPath("$.calls[0].owner.name").value("Gabi Garçom"))
                .andExpect(jsonPath("$.calls[0].mine").value(true))
                .andExpect(jsonPath("$.calls[0].quantity").value(3))
                .andExpect(jsonPath("$.calls[0].line.productName").value("Chopp 300 ml"))
                .andExpect(jsonPath("$.calls[0].line.status").value("PRONTO"))
                .andReturn().getResponse().getContentAsString(), "$.calls[0].id");
        hugo.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(1))
                .andExpect(jsonPath("$.calls[0].mine").value(false));
        // Ler não conclui a tarefa.
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(1));
        // Ninguém "assume" no garçom responsável: o chamado é da responsável.
        hugo.post(CHAMADOS + "/" + choppCall + "/claim", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAMADO_COM_RESPONSAVEL"));
        // Produção não vê a fila de atendimento.
        paulo.get(CHAMADOS).andExpect(status().isForbidden());

        // Impossibilidade: aviso para quem atende, concluído quando a produção retira a sinalização.
        String batataLinha = JsonPath.read(paulo.get("/api/v1/production/queue").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].responsible").value("Gabi Garçom"))
                .andReturn().getResponse().getContentAsString(), "$.lines[0].id");
        relogio.advance(Duration.ofSeconds(1));
        paulo.post("/api/v1/production/lines/" + batataLinha + "/impossible",
                "{\"version\":0,\"reason\":\"Acabou a batata\"}").andExpect(status().isOk());
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(2))
                .andExpect(jsonPath("$.calls[1].kind").value("IMPOSSIBILIDADE"))
                .andExpect(jsonPath("$.calls[1].line.impossible.reason").value("Acabou a batata"));
        paulo.post("/api/v1/production/lines/" + batataLinha + "/impossible/withdraw", "{\"version\":1}")
                .andExpect(status().isOk());
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(1));
        assertThat(admin().queryForObject("""
                SELECT resolution || ':' || (concluded_by = ?) FROM fastpay.attendance_call
                 WHERE production_line_id = ? AND kind = 'IMPOSSIBILIDADE'
                """, String.class, producao, UUID.fromString(batataLinha))).isEqualTo("SINALIZACAO_RETIRADA:true");

        // Ajuda permitida: o ajudante entrega o chopp da comanda da Gabi; a entrega conclui o aviso.
        String choppLinha = JsonPath.read(hugo.get(CHAMADOS).andReturn().getResponse().getContentAsString(),
                "$.calls[0].line.id");
        hugo.post("/api/v1/production/lines/" + choppLinha + "/deliver", "{\"version\":0}")
                .andExpect(status().isOk());
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(0));
        assertThat(admin().queryForObject("""
                SELECT resolution || ':' || (concluded_by = ?) FROM fastpay.attendance_call WHERE id = ?
                """, String.class, ajudante, UUID.fromString(choppCall))).isEqualTo("ENTREGUE:true");

        // Pronto pela produção abre outro aviso; o mesmo item não gera dois avisos em aberto.
        paulo.post("/api/v1/production/lines/" + batataLinha + "/start", "{\"version\":2}").andExpect(status().isOk());
        paulo.post("/api/v1/production/lines/" + batataLinha + "/ready", "{\"version\":3}").andExpect(status().isOk());
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(1))
                .andExpect(jsonPath("$.calls[0].kind").value("ITEM_PRONTO"))
                .andExpect(jsonPath("$.calls[0].line.productName").value("Batata frita"))
                .andExpect(jsonPath("$.calls[0].quantity").value(2));
        // Avisos da equipe pelo tópico do atendimento (H17); o consumidor não recebe esse tópico.
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.sync_change WHERE establishment_id = ? AND topic = 'ATENDIMENTO'
                """, Integer.class, loja)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void gerenteReatribuiOResponsavelEOsAvisosAcompanham() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser hugo = login.inUnit(ajudante, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Bia");
        gabi.post("/api/v1/orders", envio(comanda, linha(chopp, 1, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();
        String url = "/api/v1/tabs/" + comanda + "/responsible";

        // Só o gerente (ou o administrador) reatribui.
        gabi.put(url, corpoResponsavel(ajudante, 0)).andExpect(status().isForbidden());
        // Para alguém ativo da unidade que atende: produção e pessoa de outra unidade não.
        gui.put(url, corpoResponsavel(producao, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("responsibleId"));
        gui.put(url, corpoResponsavel(garcomDaOutra, 0)).andExpect(status().isBadRequest());
        gui.put(url, "{\"responsibleId\":\"" + ajudante + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("version"));
        gui.get("/api/v1/attendance/attendants").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].displayName").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "Dona Rita", "Gui Gerente", "Gabi Garçom", "Hugo Ajudante")));
        gabi.get("/api/v1/attendance/attendants").andExpect(status().isForbidden());

        gui.put(url, corpoResponsavel(ajudante, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.responsibleName").value("Hugo Ajudante"))
                .andExpect(jsonPath("$.version").value(1));
        // A versão lida protege de sobrescrever outra mudança.
        gui.put(url, corpoResponsavel(garcom, 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));

        // O aviso em aberto passa ao novo responsável junto com a comanda.
        hugo.get(CHAMADOS).andExpect(jsonPath("$.calls[0].owner.name").value("Hugo Ajudante"))
                .andExpect(jsonPath("$.calls[0].mine").value(true));
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls[0].mine").value(false));
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.audit_event WHERE action = 'comanda.responsavel_alterado' AND actor_id = ?
                """, Integer.class, gerente)).isEqualTo(1);
    }

    @Test
    void centralTemClaimExclusivoETrocarModalidadeNaoMigraChamadosAntigos() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser hugo = login.inUnit(ajudante, loja);
        Browser gui = login.inUnit(gerente, loja);

        // Comanda aberta no garçom responsável, antes da troca de modalidade.
        String antiga = comanda(gabi, "Caio");
        mudarParaCentral();
        relogio.advance(Duration.ofSeconds(1));
        String nova = JsonPath.read(gabi.post("/api/v1/tabs", "{\"name\":\"Duda\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceMode").value("CENTRAL"))
                .andExpect(jsonPath("$.responsibleId").value(nullValue()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        gabi.post("/api/v1/orders", envio(antiga, linha(chopp, 1, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();
        relogio.advance(Duration.ofSeconds(1));
        gabi.post("/api/v1/orders", envio(nova, linha(chopp, 2, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();

        // A comanda antiga continua no garçom responsável; a nova vai para a central, sem dono.
        String fila = gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(2))
                .andExpect(jsonPath("$.calls[0].mode").value("GARCOM_RESPONSAVEL"))
                .andExpect(jsonPath("$.calls[0].owner.name").value("Gabi Garçom"))
                .andExpect(jsonPath("$.calls[1].mode").value("CENTRAL"))
                .andExpect(jsonPath("$.calls[1].owner").value(nullValue()))
                .andExpect(jsonPath("$.calls[1].line.responsible").value("Central"))
                .andReturn().getResponse().getContentAsString();
        String central = JsonPath.read(fila, "$.calls[1].id");
        String claim = CHAMADOS + "/" + central + "/claim";

        // Dois funcionários assumem ao mesmo tempo: só um fica com o chamado.
        List<ResultActions> respostas = emParalelo(List.<Callable<ResultActions>>of(
                () -> gabi.post(claim, "{}"), () -> hugo.post(claim, "{}")));
        List<Integer> codigos = new ArrayList<>();
        for (ResultActions r : respostas) {
            codigos.add(r.andReturn().getResponse().getStatus());
        }
        assertThat(codigos).containsExactlyInAnyOrder(200, 409);
        UUID dono = admin().queryForObject("SELECT claimed_by FROM fastpay.attendance_call WHERE id = ?", UUID.class,
                UUID.fromString(central));
        Browser vencedor = dono.equals(garcom) ? gabi : hugo;
        Browser perdedor = dono.equals(garcom) ? hugo : gabi;
        UUID quemPerdeu = dono.equals(garcom) ? ajudante : garcom;
        perdedor.post(claim, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAMADO_JA_ASSUMIDO"))
                .andExpect(jsonPath("$.holder").isNotEmpty());
        // Assumir de novo o próprio chamado não muda nada.
        vencedor.post(claim, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.version").value(1));
        perdedor.get(CHAMADOS).andExpect(jsonPath("$.calls[1].mine").value(false))
                .andExpect(jsonPath("$.calls[1].owner.id").value(dono.toString()));

        // Gerente reatribui o chamado da central, com a versão lida.
        String assign = CHAMADOS + "/" + central + "/assign";
        vencedor.post(assign, corpoAssign(quemPerdeu, 1)).andExpect(status().isForbidden());
        gui.post(assign, corpoAssign(quemPerdeu, 0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gui.post(assign, corpoAssign(producao, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("assigneeId"));
        gui.post(assign, corpoAssign(quemPerdeu, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.id").value(quemPerdeu.toString()))
                .andExpect(jsonPath("$.version").value(2));
        perdedor.get(CHAMADOS).andExpect(jsonPath("$.calls[1].mine").value(true));
        // O chamado do garçom responsável não é reatribuído por chamado; nem a comanda da central por responsável.
        String antigoChamado = JsonPath.read(fila, "$.calls[0].id");
        gui.post(CHAMADOS + "/" + antigoChamado + "/assign", corpoAssign(ajudante, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHAMADO_COM_RESPONSAVEL"));
        gui.put("/api/v1/tabs/" + nova + "/responsible", corpoResponsavel(ajudante, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATENDIMENTO_CENTRAL"));

        // Ajuda: quem não assumiu também entrega; o chamado concluído não muda mais.
        String linha = JsonPath.read(fila, "$.calls[1].line.id");
        vencedor.post("/api/v1/production/lines/" + linha + "/deliver", "{\"version\":0}")
                .andExpect(status().isOk());
        vencedor.post(claim, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAMADO_CONCLUIDO"));
        gabi.get(CHAMADOS).andExpect(jsonPath("$.calls.length()").value(1))
                .andExpect(jsonPath("$.calls[0].mode").value("GARCOM_RESPONSAVEL"));
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.audit_event WHERE subject_id = ?
                   AND action IN ('chamado.assumido', 'chamado.reatribuido')
                """, Integer.class, UUID.fromString(central))).isEqualTo(2);
    }

    @Test
    void outraUnidadeNaoVeNemAssumeEORuntimeNaoApagaChamados() throws Exception {
        mudarParaCentral();
        Browser gabi = login.inUnit(garcom, loja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        String comanda = comanda(gabi, "Eva");
        gabi.post("/api/v1/orders", envio(comanda, linha(chopp, 1, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();
        String chamado = JsonPath.read(gabi.get(CHAMADOS).andReturn().getResponse().getContentAsString(),
                "$.calls[0].id");

        otto.get(CHAMADOS).andExpect(status().isOk()).andExpect(jsonPath("$.calls.length()").value(0));
        otto.post(CHAMADOS + "/" + chamado + "/claim", "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAMADO_NAO_ENCONTRADO"));

        JdbcTemplate runtime = new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl(), "fastpay_app", appPassword()));
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.attendance_call", Integer.class)).isZero();
        assertThatThrownBy(() -> runtime.update("DELETE FROM fastpay.attendance_call"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("UPDATE fastpay.attendance_call SET kind = 'IMPOSSIBILIDADE'"))
                .rootCause().hasMessageContaining("permission denied");
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

    private static String corpoResponsavel(UUID pessoa, long versao) {
        return "{\"responsibleId\":\"" + pessoa + "\",\"version\":" + versao + "}";
    }

    private static String corpoAssign(UUID pessoa, long versao) {
        return "{\"assigneeId\":\"" + pessoa + "\",\"version\":" + versao + "}";
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

    private static String linha(UUID produto, int quantidade, String observacao, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":"
                + (observacao == null ? "null" : "\"" + observacao + "\"") + ",\"expectedPrice\":\"" + preco + "\"}";
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
