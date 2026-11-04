package tech.malyah.fastpay.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Fila de produção e entrega por linha pela API (H18, FR-18–FR-22, CA-08, D-06), com PostgreSQL real, RLS, Spring
 * Session e CSRF. Cobre a validação exigida no backlog: permissões por papel e unidade, linha impossível não avança,
 * entrega com versão desatualizada (contrato com transferência e cancelamento), relógios de etapa e uma linha só para
 * quantidade maior que 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class ProductionIntegrationTest extends PostgresIntegrationTest {

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
    UUID garcom;
    UUID producao;
    UUID producaoDaOutra;
    UUID garcomDaOutra;
    UUID cozinha;
    UUID bar;
    UUID batata;
    UUID caipirinha;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Produção");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        producaoDaOutra = fixtures.identity("Olga Outra");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(producaoDaOutra, outraLoja, MembershipRole.PRODUCAO);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
        cozinha = setor(loja, "Cozinha");
        bar = setor(loja, "Bar");
        batata = produto(loja, "Batata frita", "35.90", cozinha);
        caipirinha = produto(loja, "Caipirinha", "24.00", bar);
        chopp = produto(loja, "Chopp 300 ml", "9.90", null);
    }

    @Test
    void filaPorSetorComRelogiosDeEtapaEEntregaDaLinhaInteira() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Ana", "5");
        Instant envio = relogio.instant();
        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 3, "sem sal", "35.90"),
                linha(caipirinha, 1, null, "24.00"), linha(chopp, 2, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();

        // Fila: só o que tem preparo, das mais antigas às mais novas; quantidade 3 é uma linha só; sem valores.
        String batataId = JsonPath.read(paulo.get(FILA).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].note").value("sem sal"))
                .andExpect(jsonPath("$.lines[0].status").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.lines[0].sectorName").value("Cozinha"))
                .andExpect(jsonPath("$.lines[0].delayLimitMinutes").value(15))
                .andExpect(jsonPath("$.lines[0].late").value(false))
                .andExpect(jsonPath("$.lines[0].responsible").value("Gabi Garçom"))
                .andExpect(jsonPath("$.lines[0].destinations.length()").value(1))
                .andExpect(jsonPath("$.lines[0].destinations[0].tabId").value(comanda))
                .andExpect(jsonPath("$.lines[0].destinations[0].name").value("Ana"))
                .andExpect(jsonPath("$.lines[0].destinations[0].table").value("5"))
                .andExpect(jsonPath("$.lines[0].destinations[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].sentAt").value(envio.toString()))
                .andExpect(jsonPath("$.lines[0].version").value(0))
                .andExpect(jsonPath("$.lines[1].productName").value("Caipirinha"))
                .andExpect(content().string(not(containsString("35.90"))))
                .andExpect(content().string(not(containsString("rice"))))
                .andReturn().getResponse().getContentAsString(), "$.lines[0].id");
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.production_line p JOIN fastpay.order_line l ON l.id = p.order_line_id
                 WHERE l.product_id = ?
                """, Integer.class, batata)).isEqualTo(1);
        paulo.get(FILA + "?sectorId=" + bar).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Caipirinha"));
        String linha = "/api/v1/production/lines/" + batataId;

        // Relógios: início do preparo e da etapa atual; a versão lida impede avançar duas vezes.
        relogio.advance(Duration.ofMinutes(2));
        Instant inicio = relogio.instant();
        paulo.post(linha + "/start", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_PREPARO"))
                .andExpect(jsonPath("$.startedAt").value(inicio.toString()))
                .andExpect(jsonPath("$.statusSince").value(inicio.toString()))
                .andExpect(jsonPath("$.version").value(1));
        paulo.post(linha + "/start", "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        paulo.post(linha + "/start", "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));
        paulo.post(linha + "/deliver", "{\"version\":1}").andExpect(status().isForbidden());
        gabi.post(linha + "/deliver", "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));

        // Atraso: passou do limite do setor desde o envio; só destaque, nada muda sozinho.
        relogio.advance(Duration.ofMinutes(14));
        paulo.get(FILA).andExpect(jsonPath("$.lines[0].late").value(true))
                .andExpect(jsonPath("$.lines[0].status").value("EM_PREPARO"))
                .andExpect(jsonPath("$.now").value(relogio.instant().toString()));
        Instant pronto = relogio.instant();
        paulo.post(linha + "/ready", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRONTO"))
                .andExpect(jsonPath("$.readyAt").value(pronto.toString()));
        paulo.get(FILA).andExpect(jsonPath("$.lines.length()").value(1));

        // Entrega: com preparo e sem preparo (o chopp nasceu pronto), a linha inteira de uma vez.
        String choppId = JsonPath.read(gabi.get(ENTREGAS).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.lines[0].status").value("PRONTO"))
                .andExpect(jsonPath("$.lines[1].productName").value("Chopp 300 ml"))
                .andExpect(jsonPath("$.lines[1].sectorId").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].delayLimitMinutes").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].late").value(false))
                .andExpect(jsonPath("$.lines[1].readyAt").value(envio.toString()))
                .andExpect(jsonPath("$.lines[1].quantity").value(2))
                .andReturn().getResponse().getContentAsString(), "$.lines[1].id");
        gabi.post("/api/v1/production/lines/" + choppId + "/deliver", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTREGUE"));

        // Mudança concorrente de destinos (transferência H25, cancelamento H23) incrementa a versão: a entrega com os
        // destinos de antes é recusada e precisa ser reconfirmada.
        admin().update("UPDATE fastpay.production_line SET version = version + 1 WHERE id = ?",
                UUID.fromString(batataId));
        gabi.post(linha + "/deliver", "{\"version\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        relogio.advance(Duration.ofMinutes(1));
        Instant entrega = relogio.instant();
        gabi.post(linha + "/deliver", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTREGUE"))
                .andExpect(jsonPath("$.deliveredAt").value(entrega.toString()))
                .andExpect(jsonPath("$.late").value(false));
        gabi.get(ENTREGAS).andExpect(jsonPath("$.lines.length()").value(0));

        // Histórico: quem, quando, de onde para onde.
        assertThat(admin().queryForList("""
                SELECT action || ':' || from_status || '>' || to_status || ':' || line_version
                  FROM fastpay.production_event WHERE production_line_id = ? ORDER BY performed_at
                """, String.class, UUID.fromString(batataId))).containsExactly(
                "INICIADO:AGUARDANDO_PREPARO>EM_PREPARO:1", "PRONTO:EM_PREPARO>PRONTO:2",
                "ENTREGUE:PRONTO>ENTREGUE:4");
        assertThat(admin().queryForList("""
                SELECT performed_by FROM fastpay.production_event WHERE production_line_id = ? ORDER BY performed_at
                """, UUID.class, UUID.fromString(batataId))).containsExactly(producao, producao, garcom);
        // Cada etapa avisa a comanda (H17); o recebimento já tinha avisado uma vez.
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.sync_change WHERE topic = 'PRODUCAO' AND tab_id = ?
                """, Integer.class, UUID.fromString(comanda))).isEqualTo(5);
    }

    @Test
    void impossivelTemMotivoBloqueiaOAvancoEApareceParaQuemAtende() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser paulo = login.inUnit(producao, loja);
        String comanda = comanda(gabi, "Bia", null);
        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 2, null, "35.90"), linha(chopp, 1, null, "9.90")))
                .andExpect(status().isOk());
        rodarJobs();
        String id = JsonPath.read(paulo.get(FILA).andReturn().getResponse().getContentAsString(), "$.lines[0].id");
        String linha = "/api/v1/production/lines/" + id;

        paulo.post(linha + "/impossible", "{\"version\":0,\"reason\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        paulo.post(linha + "/impossible", "{\"version\":0,\"reason\":\"Acabou   a batata\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PREPARO"))
                .andExpect(jsonPath("$.impossible.reason").value("Acabou a batata"))
                .andExpect(jsonPath("$.version").value(1));
        paulo.post(linha + "/start", "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINHA_IMPOSSIVEL"));
        paulo.post(linha + "/impossible", "{\"version\":1,\"reason\":\"De novo\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));
        // Quem atende vê a impossibilidade junto com os prontos (o chopp sem preparo).
        gabi.get(ENTREGAS).andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].impossible.reason").value("Acabou a batata"))
                .andExpect(jsonPath("$.lines[1].impossible").value(nullValue()));

        // Retirada (o insumo chegou): volta a avançar; em preparo, sinalizar de novo bloqueia o pronto.
        paulo.post(linha + "/impossible/withdraw", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.impossible").value(nullValue()));
        paulo.post(linha + "/start", "{\"version\":2}").andExpect(status().isOk());
        paulo.post(linha + "/impossible", "{\"version\":3,\"reason\":\"Fritadeira quebrou\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_PREPARO"));
        paulo.post(linha + "/ready", "{\"version\":4}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINHA_IMPOSSIVEL"));
        paulo.post(linha + "/impossible/withdraw", "{\"version\":4}").andExpect(status().isOk());
        paulo.post(linha + "/impossible/withdraw", "{\"version\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));

        // Sem preparo não passa pela produção: não há o que sinalizar.
        String chopp = JsonPath.read(gabi.get(ENTREGAS).andReturn().getResponse().getContentAsString(),
                "$.lines[0].id");
        paulo.post("/api/v1/production/lines/" + chopp + "/impossible", "{\"version\":0,\"reason\":\"Sem copo\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ETAPA_INVALIDA"));

        assertThat(admin().queryForList("""
                SELECT action || ':' || coalesce(reason, '-') FROM fastpay.production_event
                 WHERE production_line_id = ? ORDER BY performed_at, line_version
                """, String.class, UUID.fromString(id))).containsExactly("IMPOSSIVEL:Acabou a batata",
                "IMPOSSIVEL_RETIRADO:-", "INICIADO:-", "IMPOSSIVEL:Fritadeira quebrou", "IMPOSSIVEL_RETIRADO:-");
        // O banco também recusa impossibilidade depois do pronto e etapa sem o instante dela.
        assertThatThrownBy(() -> admin().update("""
                UPDATE fastpay.production_line SET status = 'PRONTO', ready_at = now(), impossible_at = now(),
                       impossible_by = ?, impossible_reason = 'Teste do banco' WHERE id = ?
                """, producao, UUID.fromString(id))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> admin().update("UPDATE fastpay.production_line SET status = 'ENTREGUE' WHERE id = ?",
                UUID.fromString(id))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permissoesPorPapelEUnidade() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser paulo = login.inUnit(producao, loja);
        Browser rita = login.inUnit(dono, loja);
        Browser olga = login.inUnit(producaoDaOutra, outraLoja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        String comanda = comanda(gabi, "Caio", "2");
        gabi.post("/api/v1/orders", envio(comanda, linha(batata, 1, null, "35.90"))).andExpect(status().isOk());
        rodarJobs();
        String id = JsonPath.read(paulo.get(FILA).andReturn().getResponse().getContentAsString(), "$.lines[0].id");
        String linha = "/api/v1/production/lines/" + id;

        // Garçom não opera a fila; produção não entrega nem vê a lista de entrega.
        gabi.get(FILA).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        gabi.post(linha + "/start", "{\"version\":0}").andExpect(status().isForbidden());
        gabi.post(linha + "/impossible", "{\"version\":0,\"reason\":\"Acabou\"}").andExpect(status().isForbidden());
        paulo.get(ENTREGAS).andExpect(status().isForbidden());

        // Outra unidade não vê nem mexe na linha; id inexistente também é 404.
        olga.get(FILA).andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(0));
        olga.post(linha + "/start", "{\"version\":0}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINHA_NAO_ENCONTRADA"));
        otto.get(ENTREGAS).andExpect(jsonPath("$.lines.length()").value(0));
        paulo.post("/api/v1/production/lines/" + UUID.randomUUID() + "/start", "{\"version\":0}")
                .andExpect(status().isNotFound());
        paulo.post(linha + "/start", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("version"));

        // Administrador faz as duas partes.
        rita.post(linha + "/start", "{\"version\":0}").andExpect(status().isOk());
        rita.post(linha + "/ready", "{\"version\":1}").andExpect(status().isOk());
        otto.post(linha + "/deliver", "{\"version\":2}").andExpect(status().isNotFound());
        rita.post(linha + "/deliver", "{\"version\":2}").andExpect(status().isOk());
    }

    @Test
    void entregasSimultaneasDaMesmaLinhaSoUmaPassa() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Duda", null);
        gabi.post("/api/v1/orders", envio(comanda, linha(chopp, 4, null, "9.90"))).andExpect(status().isOk());
        rodarJobs();
        String id = JsonPath.read(gabi.get(ENTREGAS).andReturn().getResponse().getContentAsString(), "$.lines[0].id");

        List<Callable<ResultActions>> tentativas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Browser aparelho = login.inUnit(garcom, loja);
            tentativas.add(() -> aparelho.post("/api/v1/production/lines/" + id + "/deliver", "{\"version\":0}"));
        }
        List<Integer> respostas = new ArrayList<>();
        for (ResultActions r : emParalelo(tentativas)) {
            respostas.add(r.andReturn().getResponse().getStatus());
        }
        assertThat(respostas).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.production_event WHERE production_line_id = ? AND action = 'ENTREGUE'
                """, Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void limiteDeAtrasoDoSetorPeloCatalogo() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String sushi = JsonPath.read(rita.post("/api/v1/catalog/sectors", "{\"name\":\"Sushi\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.delayLimitMinutes").value(15))
                .andReturn().getResponse().getContentAsString(), "$.id");
        rita.post("/api/v1/catalog/sectors", "{\"name\":\"Forno\",\"delayLimitMinutes\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("delayLimitMinutes"));
        rita.put("/api/v1/catalog/sectors/" + sushi, "{\"name\":\"Sushi\",\"delayLimitMinutes\":241,\"version\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("delayLimitMinutes"));
        rita.put("/api/v1/catalog/sectors/" + sushi, "{\"name\":\"Sushi\",\"delayLimitMinutes\":30,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.delayLimitMinutes").value(30))
                .andExpect(jsonPath("$.version").value(1));
        // Renomear sem informar o limite mantém o atual.
        rita.put("/api/v1/catalog/sectors/" + sushi, "{\"name\":\"Sushi bar\",\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.delayLimitMinutes").value(30));
        rita.get("/api/v1/catalog").andExpect(jsonPath("$.sectors[?(@.name == 'Sushi bar')].delayLimitMinutes")
                .value(30));
        assertThat(admin().queryForList("""
                SELECT action || ':' || coalesce(detail, '-') FROM fastpay.audit_event WHERE subject_id = ?
                """, String.class, UUID.fromString(sushi))).containsExactlyInAnyOrder("setor.criado:atraso 15 min",
                "setor.atraso_alterado:15>30 min", "setor.renomeado:-");
    }

    private void rodarJobs() {
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
    }

    private String comanda(Browser quem, String nome, String mesa) throws Exception {
        String corpo = "{\"name\":\"" + nome + "\"" + (mesa == null ? "" : ",\"table\":\"" + mesa + "\"") + "}";
        return JsonPath.read(quem.post("/api/v1/tabs", corpo).andExpect(status().isOk())
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
