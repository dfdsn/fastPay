package tech.malyah.fastpay.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
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
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Sincronização operacional pela API (H17, FR-27, NFR-02, NFR-11), com PostgreSQL real, RLS, Spring Session e o envio
 * às conexões chamado pelo teste. Cobre a validação exigida no backlog: queda e reconexão com replay pelo cursor, cursor
 * perdido (retenção, inválido, de outra sequência), usuário sem vínculo, sessão expirada e encerrada, e sessão que não é
 * renovada pelo heartbeat nem pelo polling. Também: isolamento entre unidades, filtro do consumidor pela comanda e sem
 * produção, sequência sem buracos com escrita concorrente, aviso desfeito com o fato e limite de conexões.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class SyncIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    Streams streams;

    @Autowired
    Tabs tabs;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID garcom;
    UUID vinculoGarcom;
    UUID garcomDaOutra;
    UUID cozinha;
    UUID batata;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar Sincronizado");
        outraLoja = fixtures.establishment("Bar Vizinho");
        dono = fixtures.identity("Dona Rita");
        garcom = fixtures.identity("Gabi Garçom");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
        cozinha = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.sector (id, establishment_id, name, created_by, created_at, updated_by, updated_at)
                VALUES (?, ?, 'Cozinha', ?, now(), ?, now())
                """, cozinha, loja, dono, dono);
        batata = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.product (id, establishment_id, name, category, price, sector_id, created_by,
                                             created_at, updated_by, updated_at)
                VALUES (?, ?, 'Batata frita', 'Geral', 35.90, ?, ?, now(), ?, now())
                """, batata, loja, cozinha, dono, dono);
    }

    @AfterEach
    void fecharConexoes() {
        streams.closeAll();
    }

    @Test
    void avisaSoAUnidadeDoFatoEReconexaoRecebeOQuePerdeuPeloCursor() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        Stream daGabi = abrir(gabi, "/api/v1/sync/stream");
        Stream doOtto = abrir(otto, "/api/v1/sync/stream");

        // Nada em cache e proxy sem buffer; a API comum também sai sem cache (especificação §2).
        assertThat(daGabi.result().getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(daGabi.result().getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        gabi.get("/api/v1/tabs").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        // Sem cursor, a conexão pede leitura completa e segue da última posição da unidade.
        Event pronto = daGabi.events().getFirst();
        assertThat(pronto.name()).isEqualTo("ready");
        assertThat(pronto.json("$.resync", Boolean.class)).isTrue();
        String inicio = pronto.json("$.cursor", String.class);

        String comanda = comanda(gabi, "Ana");
        streams.dispatch();
        Event aberta = daGabi.events().getLast();
        assertThat(aberta.name()).isEqualTo("change");
        assertThat(aberta.json("$.topic", String.class)).isEqualTo("COMANDA");
        assertThat(aberta.json("$.tabId", String.class)).isEqualTo(comanda);
        assertThat(aberta.id()).isEqualTo(aberta.json("$.cursor", String.class));
        // O aviso não leva dados da comanda: só tópico, comanda e cursor.
        assertThat(aberta.data()).doesNotContain("Ana");

        // Queda: a reconexão manda o último cursor recebido e recebe o que veio depois, na ordem.
        enviar(gabi, comanda);
        worker.runOnce();
        Stream reconectada = abrir(gabi, "/api/v1/sync/stream?after=" + aberta.id());
        assertThat(reconectada.events().getFirst().json("$.resync", Boolean.class)).isFalse();
        streams.dispatch();
        assertThat(reconectada.changes()).extracting(e -> e.json("$.topic", String.class))
                .containsExactly("PEDIDO", "PRODUCAO");
        // O EventSource do navegador reconecta sozinho com Last-Event-ID: mesmo replay.
        Stream peloHeader = new Stream(gabi.send(get("/api/v1/sync/stream").header("Last-Event-ID", inicio))
                .andExpect(request().asyncStarted()).andReturn());
        streams.dispatch();
        assertThat(peloHeader.changes()).extracting(e -> e.json("$.topic", String.class))
                .containsExactly("COMANDA", "PEDIDO", "PRODUCAO");

        // Outra unidade não recebe nada, nem pelo polling.
        assertThat(doOtto.changes()).isEmpty();
        otto.background("/api/v1/sync/changes?after=0").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.changes.length()").value(0));
        // Polling da equipe: o mesmo que o SSE.
        gabi.background("/api/v1/sync/changes?after=" + inicio).andExpect(status().isOk())
                .andExpect(jsonPath("$.resync").value(false))
                .andExpect(jsonPath("$.changes[*].topic").value(org.hamcrest.Matchers.contains("COMANDA", "PEDIDO",
                        "PRODUCAO")))
                .andExpect(jsonPath("$.changes[0].tabId").value(comanda));
    }

    @Test
    void cursorPerdidoInvalidoOuDeOutraSequenciaPedeLeituraCompleta() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        comanda(gabi, "Ana");
        comanda(gabi, "Beto");
        long ultimo = admin().queryForObject("SELECT last_seq FROM fastpay.sync_cursor WHERE establishment_id = ?",
                Long.class, loja);

        for (String cursor : List.of("abc", "-1", Long.toString(ultimo + 1), "9".repeat(19))) {
            gabi.background("/api/v1/sync/changes?after=" + cursor).andExpect(status().isOk())
                    .andExpect(jsonPath("$.resync").value(true))
                    .andExpect(jsonPath("$.cursor").value(Long.toString(ultimo)))
                    .andExpect(jsonPath("$.changes.length()").value(0));
        }
        gabi.background("/api/v1/sync/changes?after=" + (ultimo - 2)).andExpect(jsonPath("$.resync").value(false))
                .andExpect(jsonPath("$.changes.length()").value(2));

        // Retenção apagou o começo: o cursor antigo não tem como saber o que perdeu.
        admin().update("DELETE FROM fastpay.sync_change WHERE establishment_id = ? AND seq = ?", loja, ultimo - 1);
        gabi.background("/api/v1/sync/changes?after=" + (ultimo - 2)).andExpect(jsonPath("$.resync").value(true));
        Stream conexao = abrir(gabi, "/api/v1/sync/stream?after=" + (ultimo - 2));
        assertThat(conexao.events().getFirst().json("$.resync", Boolean.class)).isTrue();
        gabi.background("/api/v1/sync/changes?after=" + (ultimo - 1)).andExpect(jsonPath("$.resync").value(false))
                .andExpect(jsonPath("$.changes.length()").value(1));
    }

    @Test
    void semSessaoSemUnidadeOuSemVinculoNaoConectaEVinculoDesativadoEncerraAConexao() throws Exception {
        new Browser(mvc).get("/api/v1/sync/stream").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"));
        new Browser(mvc).background("/api/v1/sync/changes").andExpect(status().isUnauthorized());
        Browser semUnidade = login.signIn(garcom);
        semUnidade.get("/api/v1/sync/stream").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));

        Browser gabi = login.inUnit(garcom, loja);
        Stream conexao = abrir(gabi, "/api/v1/sync/stream");
        admin().update("UPDATE fastpay.membership SET status = 'INACTIVE', deactivated_at = now() WHERE id = ?",
                vinculoGarcom);
        // Antes da reconferência a conexão segue; ela só avisa que algo mudou, sem dados.
        streams.dispatch();
        assertThat(conexao.completed()).isFalse();
        relogio.advance(Duration.ofSeconds(10));
        streams.dispatch();
        assertThat(conexao.events().getLast().name()).isEqualTo("closed");
        assertThat(conexao.events().getLast().json("$.reason", String.class)).isEqualTo("UNIDADE_NAO_AUTORIZADA");
        assertThat(conexao.completed()).isTrue();
        assertThat(streams.count()).isZero();

        gabi.get("/api/v1/sync/stream").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
        gabi.background("/api/v1/sync/changes").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
    }

    @Test
    void heartbeatPollingEReconexaoNaoRenovamASessaoEASessaoVencidaEncerraAConexao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        List<Stream> conexoes = new ArrayList<>(List.of(abrir(gabi, "/api/v1/sync/stream")));

        // 59 minutos só com heartbeat, polling e a reconexão pedida aos 30 minutos (garçom: 1 h de inatividade).
        for (int i = 0; i < 59; i++) {
            relogio.advance(Duration.ofMinutes(1));
            streams.dispatch();
            if (conexoes.getLast().completed()) {
                assertThat(conexoes.getLast().events().getLast().json("$.reason", String.class))
                        .isEqualTo(Streams.RECONNECT);
                conexoes.add(abrir(gabi, "/api/v1/sync/stream"));
            }
            if (i % 10 == 0) {
                gabi.background("/api/v1/sync/changes").andExpect(status().isOk());
            }
        }
        assertThat(conexoes).hasSize(2);
        Stream conexao = conexoes.getLast();
        assertThat(conexoes.stream().flatMap(c -> eventos(c).stream()).filter(e -> e.name().equals("heartbeat")))
                .hasSizeGreaterThan(50);
        assertThat(conexao.completed()).isFalse();

        relogio.advance(Duration.ofMinutes(1).plusSeconds(1));
        streams.dispatch();
        assertThat(conexao.events().getLast().json("$.reason", String.class)).isEqualTo("SESSAO_ENCERRADA");
        assertThat(conexao.completed()).isTrue();
        gabi.background("/api/v1/sync/changes").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"));
        gabi.get("/api/v1/sync/stream").andExpect(status().isUnauthorized());
    }

    private static List<Event> eventos(Stream conexao) {
        try {
            return conexao.events();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void logoutETrocaDeUnidadeEncerramAConexaoEADuracaoMaximaPedeReconexao() throws Exception {
        fixtures.membership(garcom, outraLoja, MembershipRole.GARCOM);
        Browser gabi = login.inUnit(garcom, loja);
        Stream antesDaTroca = abrir(gabi, "/api/v1/sync/stream");
        gabi.put("/api/v1/session/unit", "{\"establishmentId\":\"" + outraLoja + "\"}").andExpect(status().isOk());
        Stream depoisDaTroca = abrir(gabi, "/api/v1/sync/stream");
        relogio.advance(Duration.ofSeconds(10));
        streams.dispatch();
        // A troca muda o id da sessão: a conexão da unidade anterior não vale mais.
        assertThat(antesDaTroca.completed()).isTrue();
        assertThat(depoisDaTroca.completed()).isFalse();

        gabi.post("/api/v1/auth/logout", "").andExpect(status().isNoContent());
        relogio.advance(Duration.ofSeconds(10));
        streams.dispatch();
        assertThat(depoisDaTroca.events().getLast().json("$.reason", String.class)).isEqualTo("SESSAO_ENCERRADA");
        assertThat(depoisDaTroca.completed()).isTrue();

        Browser dona = login.inUnit(dono, loja);
        Stream longa = abrir(dona, "/api/v1/sync/stream");
        dona.get("/api/v1/session/unit").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(29));
        dona.get("/api/v1/session/unit").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(1));
        streams.dispatch();
        assertThat(longa.events().getLast().json("$.reason", String.class)).isEqualTo(Streams.RECONNECT);
        assertThat(longa.completed()).isTrue();
    }

    @Test
    void consumidorRecebeSoAvisosDaPropriaComandaSemProducaoEAcessoRevogadoEncerra() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String daAna = comanda(gabi, "Ana");
        String doBeto = comanda(gabi, "Beto");
        Browser cliente = new Browser(mvc);
        String token = token(gabi.post("/api/v1/tabs/" + daAna + "/binding-qr", "{}").andExpect(status().isOk()));
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + token + "\"}").andExpect(status().isOk());

        new Browser(mvc).get("/api/v1/guest/tabs/" + daAna + "/stream").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        cliente.get("/api/v1/guest/tabs/" + doBeto + "/stream").andExpect(status().isNotFound());
        Stream daCliente = abrir(cliente, "/api/v1/guest/tabs/" + daAna + "/stream");
        String inicio = daCliente.events().getFirst().json("$.cursor", String.class);

        String versao = JsonPath.read(gabi.get("/api/v1/tabs/" + daAna).andReturn().getResponse()
                .getContentAsString(), "$.version").toString();
        String versaoDoBeto = JsonPath.read(gabi.get("/api/v1/tabs/" + doBeto).andReturn().getResponse()
                .getContentAsString(), "$.version").toString();
        gabi.put("/api/v1/tabs/" + doBeto + "/table", "{\"version\":" + versaoDoBeto + ",\"table\":\"7\"}")
                .andExpect(status().isOk());
        gabi.put("/api/v1/tabs/" + daAna + "/table", "{\"version\":" + versao + ",\"table\":\"3\"}")
                .andExpect(status().isOk());
        enviar(gabi, doBeto);
        enviar(gabi, daAna);
        worker.runOnce();
        streams.dispatch();
        assertThat(daCliente.changes()).extracting(e -> e.json("$.topic", String.class))
                .containsExactly("COMANDA", "PEDIDO");
        assertThat(daCliente.changes()).allMatch(e -> daAna.equals(e.json("$.tabId", String.class)));
        cliente.get("/api/v1/guest/tabs/" + daAna + "/changes?after=" + inicio).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[*].topic").value(org.hamcrest.Matchers.contains("COMANDA", "PEDIDO")));

        // Recuperação revoga o acesso deste aparelho: a conexão termina na reconferência.
        Browser dona = login.inUnit(dono, loja);
        dona.post("/api/v1/tabs/" + daAna + "/access-recovery",
                "{\"nameChecked\":true,\"accountCheck\":\"ITEM_DA_CONTA\",\"reason\":\"celular sem bateria\"}")
                .andExpect(status().isOk());
        relogio.advance(Duration.ofSeconds(10));
        streams.dispatch();
        assertThat(daCliente.events().getLast().json("$.reason", String.class)).isEqualTo("ACESSO_ENCERRADO");
        assertThat(daCliente.completed()).isTrue();
        cliente.get("/api/v1/guest/tabs/" + daAna + "/changes").andExpect(status().isNotFound());
    }

    @Test
    void sequenciaSemBuracosComEscritaConcorrenteEAvisoDesfeitoComOFato() throws Exception {
        List<Callable<Boolean>> aberturas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String nome = "Cliente " + i;
            aberturas.add(() -> tabs.open(garcom, loja, nome, null, null) instanceof Tabs.Done<Tabs.Tab>);
        }
        assertThat(emParalelo(aberturas)).containsOnly(true);
        assertThat(admin().queryForList("SELECT seq FROM fastpay.sync_change WHERE establishment_id = ? ORDER BY seq",
                Long.class, loja)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        // Pedido que volta para revisão (preço mudou) não grava nada, nem o aviso.
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = admin().queryForObject("SELECT id FROM fastpay.tab WHERE establishment_id = ? LIMIT 1",
                UUID.class, loja).toString();
        gabi.post("/api/v1/orders", pedido(comanda, "30.00")).andExpect(status().isConflict());
        assertThat(admin().queryForObject("SELECT last_seq FROM fastpay.sync_cursor WHERE establishment_id = ?",
                Long.class, loja)).isEqualTo(8L);
    }

    @Test
    void limiteDeConexoesSimultaneasPorSessao() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        for (int i = 0; i < 5; i++) {
            abrir(gabi, "/api/v1/sync/stream");
        }
        gabi.get("/api/v1/sync/stream").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MUITAS_CONEXOES"));
        // O polling continua valendo.
        gabi.background("/api/v1/sync/changes").andExpect(status().isOk());
    }

    private Stream abrir(Browser quem, String url) throws Exception {
        return new Stream(quem.get(url).andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn());
    }

    private String comanda(Browser quem, String nome) throws Exception {
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void enviar(Browser quem, String comanda) throws Exception {
        quem.post("/api/v1/orders", pedido(comanda, "35.90")).andExpect(status().isOk());
    }

    private String pedido(String comanda, String preco) {
        return "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"productId\":\""
                + batata + "\",\"quantity\":1,\"note\":null,\"expectedPrice\":\"" + preco + "\"}]}";
    }

    private static String token(ResultActions result) throws Exception {
        String link = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.link");
        return link.substring(link.indexOf('#') + 1);
    }

    private static <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tarefas)) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Uma conexão SSE aberta pelo MockMvc: o conteúdo cresce a cada envio. */
    private record Stream(MvcResult result) {

        List<Event> events() throws Exception {
            List<Event> events = new ArrayList<>();
            for (String block : result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n\n")) {
                String name = null;
                String id = null;
                StringBuilder data = new StringBuilder();
                for (String line : block.split("\n")) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6);
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3);
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5));
                    }
                }
                if (name != null) {
                    events.add(new Event(name, id, data.toString()));
                }
            }
            return events;
        }

        List<Event> changes() throws Exception {
            return events().stream().filter(e -> e.name().equals("change")).toList();
        }

        /** O servidor terminou a conexão (o resultado assíncrono só existe depois do fim). */
        boolean completed() {
            try {
                result.getAsyncResult(0);
                return true;
            } catch (IllegalStateException stillOpen) {
                return false;
            }
        }
    }

    private record Event(String name, String id, String data) {

        <T> T json(String path, Class<T> type) {
            return JsonPath.parse(data).read(path, type);
        }
    }
}
