package tech.malyah.fastpay.tabs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Comanda individual, visita, QR de vínculo e acesso avulso pela API (H15, FR-07–FR-12, CA-01, CA-02, CA-23), com
 * PostgreSQL real, RLS, Spring Session e CSRF. Cobre a validação exigida no backlog: QR simultâneo, substituído e
 * expirado, acesso por nome negado, acesso depois de fechar o navegador e depois da resolução + 7 dias, e a
 * recuperação com a conferência de P-07.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class TabsIntegrationTest extends PostgresIntegrationTest {

    private static final String COOKIE = "FASTPAY_ACESSO";

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    GuestAccess guestAccess;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID garcomDaOutra;
    UUID vinculoGarcom;

    @BeforeEach
    void montarLojas() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Comanda");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gil Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        garcomDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
    }

    @Test
    void garcomAbreComandasComNumeroAutomaticoNomeRepetidoEMesaOpcional() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        UUID versaoInicial = versaoVigente(loja);

        String primeira = id(gabi.post("/api/v1/tabs", "{\"name\":\"  Ana   Paula \",\"table\":\" 12 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.name").value("Ana Paula"))
                .andExpect(jsonPath("$.table").value("12"))
                .andExpect(jsonPath("$.guestLinked").value(false))
                .andExpect(jsonPath("$.settingsVersionId").value(versaoInicial.toString())));
        // Nome repetido é permitido; mesa é opcional.
        String segunda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana Paula\",\"table\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.table").value(nullValue())));
        assertThat(primeira).isNotEqualTo(segunda);

        // Mudança de configuração depois da abertura não altera a versão guardada (H14, D-08).
        UUID novaVersao = novaVersaoDaConfiguracao(loja);
        String terceira = id(gabi.post("/api/v1/tabs", "{\"name\":\"Beto\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(3)));
        gabi.get("/api/v1/tabs/" + primeira).andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsVersionId").value(versaoInicial.toString()));
        gabi.get("/api/v1/tabs/" + terceira).andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsVersionId").value(novaVersao.toString()));

        // Cada comanda nasce com a sua visita.
        assertThat(admin().queryForObject("SELECT count(DISTINCT visit_id) FROM fastpay.tab WHERE establishment_id = ?",
                Integer.class, loja)).isEqualTo(3);
        gabi.get("/api/v1/tabs").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[2].number").value(3));

        // Número é distinto só entre as ativas: encerrada libera o número (encerrar chega na H28/H29).
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(primeira));
        gabi.post("/api/v1/tabs", "{\"name\":\"Caio\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1));

        assertThat(admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE establishment_id = ? AND actor_id = ?
                """, String.class, loja, garcom)).containsOnly("comanda.aberta").hasSize(4);
    }

    @Test
    void aberturasSimultaneasNaoRepetemNumero() throws Exception {
        List<Callable<ResultActions>> aberturas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // Um garçom e um navegador por requisição simultânea (o navegador de teste não é thread-safe).
            UUID pessoa = fixtures.identity("Garçom " + i);
            fixtures.membership(pessoa, loja, MembershipRole.GARCOM);
            Browser quem = login.inUnit(pessoa, loja);
            int n = i;
            aberturas.add(() -> quem.post("/api/v1/tabs", "{\"name\":\"Cliente " + n + "\"}"));
        }
        for (ResultActions r : emParalelo(aberturas)) {
            r.andExpect(status().isOk());
        }
        assertThat(admin().queryForList("SELECT number FROM fastpay.tab WHERE establishment_id = ? ORDER BY number",
                Integer.class, loja)).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void soQuemOperaComandaAbreEVeEDadosInvalidosSaoRecusados() throws Exception {
        login.inUnit(producao, loja).post("/api/v1/tabs", "{\"name\":\"Ana\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        login.inUnit(producao, loja).get("/api/v1/tabs")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        Browser gabi = login.inUnit(garcom, loja);
        gabi.post("/api/v1/tabs", "{\"name\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DADOS_INVALIDOS")).andExpect(jsonPath("$.field").value("name"));
        gabi.post("/api/v1/tabs", "{\"name\":\"" + "a".repeat(61) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("name"));
        gabi.post("/api/v1/tabs", "{\"name\":\"Ana\",\"table\":\"" + "1".repeat(21) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("table"));

        // Comanda de outra unidade não aparece (RLS) nem por id.
        String daOutra = id(login.inUnit(garcomDaOutra, outraLoja).post("/api/v1/tabs", "{\"name\":\"Ana\"}")
                .andExpect(status().isOk()));
        gabi.get("/api/v1/tabs/" + daOutra).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NAO_ENCONTRADA"));
        gabi.post("/api/v1/tabs/" + daOutra + "/binding-qr", "{}").andExpect(status().isNotFound());
        gabi.get("/api/v1/tabs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Sem sessão de equipe, nada: o consumidor não abre comanda (CA-01).
        new Browser(mvc).post("/api/v1/tabs", "{\"name\":\"Eu mesmo\"}").andExpect(status().isUnauthorized());
        new Browser(mvc).get("/api/v1/tabs").andExpect(status().isUnauthorized());
    }

    @Test
    void trocarMesaNaoMudaTitularEPedeVersaoAtual() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\",\"table\":\"3\"}"));
        gabi.put("/api/v1/tabs/" + comanda + "/table", "{\"table\":\"7\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").value("7"))
                .andExpect(jsonPath("$.name").value("Ana"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.version").value(1));
        gabi.put("/api/v1/tabs/" + comanda + "/table", "{\"table\":\"9\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gabi.put("/api/v1/tabs/" + comanda + "/table", "{\"table\":null,\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.table").value(nullValue()));
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.audit_event WHERE subject_id = ? AND action = 'comanda.mesa_alterada'
                """, Integer.class, UUID.fromString(comanda))).isEqualTo(2);
    }

    @Test
    void qrVinculaUmaVezEPreviewNaoConsome() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\",\"table\":\"4\"}"));
        String token = token(gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.svg").value(org.hamcrest.Matchers.startsWith("<svg")))
                .andExpect(jsonPath("$.recovery").value(false)));

        Browser cliente = new Browser(mvc);
        for (int i = 0; i < 2; i++) {
            cliente.post("/api/v1/guest/bindings/preview", tokenJson(token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.establishmentName").value("Bar da Comanda"))
                    .andExpect(jsonPath("$.number").value(1))
                    .andExpect(jsonPath("$.name").value("Ana"));
        }
        assertThat(cliente.cookie(COOKIE)).isEmpty();

        cliente.post("/api/v1/guest/bindings/confirm", tokenJson(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tabId").value(comanda))
                .andExpect(jsonPath("$.table").value("4"))
                .andExpect(jsonPath("$.open").value(true));
        assertThat(cliente.cookie(COOKIE)).isPresent();

        // Uso único: o mesmo QR não vale para outro aparelho.
        Browser outro = new Browser(mvc);
        outro.post("/api/v1/guest/bindings/preview", tokenJson(token)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("QR_INVALIDO"));
        outro.post("/api/v1/guest/bindings/confirm", tokenJson(token)).andExpect(status().isGone());
        outro.get("/api/v1/guest/tabs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        cliente.get("/api/v1/guest/tabs").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Ana"));
        gabi.get("/api/v1/tabs/" + comanda).andExpect(jsonPath("$.guestLinked").value(true));

        // Com acesso ativo, outro QR é recusado: trocar de aparelho é recuperação.
        gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSUMIDOR_JA_VINCULADO"));

        assertThat(admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? ORDER BY occurred_at",
                String.class, UUID.fromString(comanda))).contains("comanda.aberta", "comanda.qr_gerado",
                "acesso.vinculado");
    }

    @Test
    void acessoSobreviveAFecharONavegadorESoDaAPropriaComanda() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String ana = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        String beto = id(gabi.post("/api/v1/tabs", "{\"name\":\"Beto\"}"));
        Browser cliente = new Browser(mvc);
        vincular(gabi, ana, cliente);

        Cookie guardado = cookieDe(cliente);
        assertThat(guardado.getMaxAge()).isGreaterThan(0);
        assertThat(guardado.isHttpOnly()).isTrue();
        assertThat(guardado.getPath()).isEqualTo("/api/v1/guest");

        // Navegador reaberto: sem sessão, só com o cookie persistente.
        Browser reaberto = new Browser(mvc);
        reaberto.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/guest/tabs/" + ana).cookie(guardado))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ana"));
        // Nome ou número não dão acesso a outra comanda (FR-08).
        reaberto.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/guest/tabs/" + beto).cookie(guardado))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        new Browser(mvc).get("/api/v1/guest/tabs/" + ana).andExpect(status().isNotFound());
        // Token forjado ou de formato errado também não.
        reaberto.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/guest/tabs/" + ana).cookie(new Cookie(COOKIE, "x".repeat(43) + ".Ana")))
                .andExpect(status().isNotFound());

        // O mesmo navegador guarda mais de uma comanda (ex.: outra visita).
        vincular(gabi, beto, cliente);
        cliente.get("/api/v1/guest/tabs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void novoQrSubstituiOAnteriorEQrVenceEmDoisMinutos() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        String primeiro = token(gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}"));
        String segundo = token(gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}"));
        Browser cliente = new Browser(mvc);
        cliente.post("/api/v1/guest/bindings/confirm", tokenJson(primeiro)).andExpect(status().isGone());

        relogio.advance(Duration.ofSeconds(119));
        cliente.post("/api/v1/guest/bindings/preview", tokenJson(segundo)).andExpect(status().isOk());
        relogio.advance(Duration.ofSeconds(1));
        cliente.post("/api/v1/guest/bindings/preview", tokenJson(segundo)).andExpect(status().isGone());
        cliente.post("/api/v1/guest/bindings/confirm", tokenJson(segundo)).andExpect(status().isGone());

        // Vencido não impede gerar outro; o novo vale.
        String terceiro = token(gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}"));
        cliente.post("/api/v1/guest/bindings/confirm", tokenJson(terceiro)).andExpect(status().isOk());
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.binding_token WHERE tab_id = ? AND invalidated_at IS NOT NULL
                """, Integer.class, UUID.fromString(comanda))).isEqualTo(2);
    }

    @Test
    void qrConfirmadoAoMesmoTempoEmDoisAparelhosVinculaSoUm() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        String token = token(gabi.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}"));

        List<Callable<Optional<GuestAccess.Bound>>> confirmacoes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            confirmacoes.add(() -> guestAccess.confirm(token));
        }
        List<Optional<GuestAccess.Bound>> resultados = emParalelo(confirmacoes);
        assertThat(resultados.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.guest_grant WHERE tab_id = ?", Integer.class,
                UUID.fromString(comanda))).isEqualTo(1);
    }

    @Test
    void recuperacaoExigePermissaoEConferenciaERevogaOAcessoAnterior() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\",\"table\":\"5\"}"));
        Browser celularPerdido = new Browser(mvc);
        vincular(gabi, comanda, celularPerdido);
        String conferencia = "{\"nameChecked\":true,\"accountCheck\":\"ITEM_DA_CONTA\","
                + "\"reason\":\"Celular descarregou; disse o nome e a cerveja pedida\"}";

        // Garçom sem a permissão delegada não recupera.
        gabi.post("/api/v1/tabs/" + comanda + "/access-recovery", conferencia).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        // Conferência incompleta: nome, item/hora e motivo são obrigatórios (P-07).
        Browser gil = login.inUnit(gerente, loja);
        gil.post("/api/v1/tabs/" + comanda + "/access-recovery",
                "{\"nameChecked\":false,\"accountCheck\":\"ITEM_DA_CONTA\",\"reason\":\"motivo ok\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("nameChecked"));
        gil.post("/api/v1/tabs/" + comanda + "/access-recovery",
                "{\"nameChecked\":true,\"accountCheck\":\"MESA\",\"reason\":\"motivo ok\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("accountCheck"));
        gil.post("/api/v1/tabs/" + comanda + "/access-recovery",
                "{\"nameChecked\":true,\"accountCheck\":\"HORA_DA_ABERTURA\",\"reason\":\" ok \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));
        celularPerdido.get("/api/v1/guest/tabs").andExpect(jsonPath("$.length()").value(1));

        // Com a delegação, o garçom recupera; o acesso anterior cai já.
        delegar(vinculoGarcom, "RECUPERAR_ACESSO_CONSUMIDOR");
        String token = token(gabi.post("/api/v1/tabs/" + comanda + "/access-recovery", conferencia)
                .andExpect(status().isOk()).andExpect(jsonPath("$.recovery").value(true)));
        celularPerdido.get("/api/v1/guest/tabs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        celularPerdido.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isNotFound());
        gabi.get("/api/v1/tabs/" + comanda).andExpect(jsonPath("$.guestLinked").value(false));

        Browser celularNovo = new Browser(mvc);
        celularNovo.post("/api/v1/guest/bindings/confirm", tokenJson(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ana")).andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.table").value("5"));

        assertThat(admin().queryForObject("""
                SELECT performed_by || ' ' || account_check || ' ' || reason FROM fastpay.access_recovery WHERE tab_id = ?
                """, String.class, UUID.fromString(comanda)))
                .isEqualTo(garcom + " ITEM_DA_CONTA Celular descarregou; disse o nome e a cerveja pedida");
        assertThat(admin().queryForObject("""
                SELECT revoke_reason || ' ' || revoked_by FROM fastpay.guest_grant WHERE tab_id = ? AND revoked_at IS NOT NULL
                """, String.class, UUID.fromString(comanda))).isEqualTo("RECUPERACAO " + garcom);
        assertThat(admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ?", String.class,
                UUID.fromString(comanda))).contains("acesso.recuperado", "acesso.revinculado");
    }

    @Test
    void recuperarSemAcessoAtivoBastaMostrarOQr() throws Exception {
        Browser gil = login.inUnit(gerente, loja);
        String comanda = id(gil.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        gil.post("/api/v1/tabs/" + comanda + "/access-recovery",
                "{\"nameChecked\":true,\"accountCheck\":\"HORA_DA_ABERTURA\",\"reason\":\"Cliente pediu\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEM_ACESSO_A_RECUPERAR"));
    }

    @Test
    void acessoAvulsoValeAteSeteDiasDepoisDaResolucaoEPendenciaSuspendeOPrazo() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        Browser cliente = new Browser(mvc);
        vincular(gabi, comanda, cliente);
        UUID id = UUID.fromString(comanda);

        // Encerrada com pendência (ex.: chamado aberto no dia 7, CA-23): sem prazo, o acesso continua.
        Instant agora = relogio.instant();
        admin().update("UPDATE fastpay.tab SET closed_at = ?, access_until = NULL WHERE id = ?", Timestamp.from(agora),
                id);
        relogio.advance(Duration.ofDays(30));
        cliente.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));

        // Resolvida agora: mais 7 dias e acabou (o prazo é gravado pelas histórias de encerramento/chamado).
        Instant resolucao = relogio.instant();
        admin().update("UPDATE fastpay.tab SET access_until = ? WHERE id = ?",
                Timestamp.from(resolucao.plus(Duration.ofDays(7))), id);
        relogio.advance(Duration.ofDays(7).minusSeconds(1));
        cliente.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isOk());
        relogio.advance(Duration.ofSeconds(1));
        cliente.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isNotFound());
        // Acesso que não vale mais sai do cookie.
        cliente.get("/api/v1/guest/tabs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(cliente.cookie(COOKIE)).isEmpty();
    }

    @Test
    void acessoDoConsumidorNaoVeComandaDeOutraUnidadeNemComTokenTrocado() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String nossa = id(gabi.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        String deles = id(otto.post("/api/v1/tabs", "{\"name\":\"Ana\"}"));
        Browser cliente = new Browser(mvc);
        vincular(gabi, nossa, cliente);
        cliente.get("/api/v1/guest/tabs/" + deles).andExpect(status().isNotFound());
        // Equipe da outra unidade não consulta nossa comanda nem gera QR dela.
        otto.get("/api/v1/tabs/" + nossa).andExpect(status().isNotFound());
        otto.post("/api/v1/tabs/" + nossa + "/binding-qr", "{}").andExpect(status().isNotFound());
    }

    // Apoio

    private void vincular(Browser equipe, String comanda, Browser cliente) throws Exception {
        String token = token(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}").andExpect(status().isOk()));
        cliente.post("/api/v1/guest/bindings/confirm", tokenJson(token)).andExpect(status().isOk());
    }

    private static Cookie cookieDe(Browser cliente) {
        return cliente.storedCookie(COOKIE).orElseThrow();
    }

    private void delegar(UUID vinculo, String permissao) {
        admin().update("""
                INSERT INTO fastpay.membership_permission (id, establishment_id, membership_id, permission, granted_by, granted_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), loja, vinculo, permissao, dono);
    }

    private UUID versaoVigente(UUID unidade) {
        return admin().queryForObject("""
                SELECT id FROM fastpay.unit_settings_version WHERE establishment_id = ? ORDER BY number DESC LIMIT 1
                """, UUID.class, unidade);
    }

    private UUID novaVersaoDaConfiguracao(UUID unidade) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.unit_settings_version (id, establishment_id, number, service_enabled, service_percent,
                       service_base, service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                       discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode, created_by, created_at)
                SELECT ?, establishment_id, number + 1, true, 12, service_base, service_includes_couvert, true,
                       'AUTOMATICO', 15.00, discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                       ?, now()
                  FROM fastpay.unit_settings_version WHERE establishment_id = ? ORDER BY number DESC LIMIT 1
                """, id, dono, unidade);
        return id;
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

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String token(ResultActions result) throws Exception {
        String link = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.link");
        assertThat(link).contains("/c#");
        return link.substring(link.indexOf('#') + 1);
    }

    private static String tokenJson(String token) {
        return "{\"token\":\"" + token + "\"}";
    }
}
