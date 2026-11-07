package tech.malyah.fastpay.tabs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.GoogleIdTokenVerifier.GoogleIdentity;
import tech.malyah.fastpay.identity.infrastructure.GoogleOidcClient;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Login Google opcional e histórico do consumidor pela API (H27, FR-04, FR-10, P-13 e T-03 aprovadas em 07/11/2026),
 * com PostgreSQL real, RLS, Spring Session e CSRF. O Google é simulado só na troca do código pelo ID token (a
 * conferência do token tem teste próprio); o resto é o fluxo real: state/nonce/PKCE na sessão, uso único e prazo,
 * aviso de privacidade, conta separada da equipe, vínculo com acesso válido e confirmação, histórico entre lojas sem
 * expor terceiros e prazos da sessão. Cobre a validação exigida no backlog: OAuth com falha/negado, conta Google
 * diferente, login sem grant, associação repetida, privacidade e prazos de sessão.
 */
@SpringBootTest(properties = {
        "fastpay.consumer.google.client-id=cliente-teste.apps.googleusercontent.com",
        "fastpay.consumer.google.client-secret=segredo-de-teste",
        "fastpay.consumer.google.redirect-uri=http://127.0.0.1:8088/api/v1/consumer/google/callback" })
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class ConsumerGoogleIntegrationTest extends PostgresIntegrationTest {

    private static final String AVISO = "2026-11-07";

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate runtime;

    @MockitoSpyBean
    GoogleOidcClient google;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID garcom;
    UUID garcomDaOutra;
    UUID gerente;
    String rodada = UUID.randomUUID().toString();

    @BeforeEach
    void montarLojas() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Histórico");
        outraLoja = fixtures.establishment("Outro Bar");
        garcom = fixtures.identity("Gabi Garçom");
        garcomDaOutra = fixtures.identity("Otto Outro");
        gerente = fixtures.identity("Gil Gerente");
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        fixtures.membership(garcomDaOutra, outraLoja, MembershipRole.GARCOM);
    }

    // Login Google

    @Test
    void pedidoAoGoogleUsaEscoposMinimosStateNoncePkceERetornoSeguro() throws Exception {
        Browser cliente = new Browser(mvc);
        String destino = location(cliente.get("/api/v1/consumer/google/start?returnTo=https://malicioso.example")
                .andExpect(status().isFound()));
        Map<String, List<String>> query = UriComponentsBuilder.fromUriString(destino).build().getQueryParams();

        assertThat(destino).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(query.get("scope")).containsExactly("openid%20email");
        assertThat(query.get("response_type")).containsExactly("code");
        assertThat(query.get("code_challenge_method")).containsExactly("S256");
        assertThat(query.get("state").getFirst()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(query.get("nonce").getFirst()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(query.get("code_challenge").getFirst()).hasSize(43);
        assertThat(query.get("redirect_uri").getFirst())
                .isEqualTo("http://127.0.0.1:8088/api/v1/consumer/google/callback");

        // Endereço de retorno de outro site é trocado pela tela do consumidor (sem redirect aberto).
        googleDevolve(new GoogleIdentity(s("sub-retorno"), "retorno@exemplo.com", true));
        assertThat(location(callback(cliente, query.get("state").getFirst(), "codigo")))
                .startsWith("/privacidade-google?returnTo=/minhas-comandas");
    }

    @Test
    void primeiroLoginMostraAvisoDePrivacidadeERecusarNaoGuardaNada() throws Exception {
        Browser cliente = new Browser(mvc);
        googleDevolve(new GoogleIdentity(s("sub-recusa"), "Recusa@Exemplo.com", true));
        String state = iniciar(cliente, "/minhas-comandas");

        assertThat(location(callback(cliente, state, "codigo"))).isEqualTo("/privacidade-google?returnTo=/minhas-comandas");
        cliente.get("/api/v1/consumer/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.signedIn").value(false))
                .andExpect(jsonPath("$.pendingEmail").value("recusa@exemplo.com"))
                .andExpect(jsonPath("$.noticeVersion").value(AVISO));
        assertThat(contas("sub-recusa")).isZero();

        cliente.post("/api/v1/consumer/privacy/decline", "{}").andExpect(status().isNoContent());
        cliente.get("/api/v1/consumer/session").andExpect(jsonPath("$.pendingEmail").doesNotExist());
        cliente.post("/api/v1/consumer/privacy/accept", "{\"noticeVersion\":\"" + AVISO + "\"}")
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LOGIN_GOOGLE_EXPIRADO"));
        assertThat(contas("sub-recusa")).isZero();
        cliente.get("/api/v1/consumer/history").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONSUMIDOR_SEM_LOGIN"));
    }

    @Test
    void aceitarOAvisoCriaContaSoComDadosMinimosEProximoLoginEntraDireto() throws Exception {
        Browser cliente = new Browser(mvc);
        googleDevolve(new GoogleIdentity(s("sub-ana"), "  Ana@Exemplo.com ", true));
        callback(cliente, iniciar(cliente, "/meu-historico"), "codigo");

        cliente.post("/api/v1/consumer/privacy/accept", "{\"noticeVersion\":\"versao-velha\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVISO_DESATUALIZADO"));
        cliente.post("/api/v1/consumer/privacy/accept", "{\"noticeVersion\":\"" + AVISO + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.returnTo").value("/meu-historico"));
        cliente.get("/api/v1/consumer/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.signedIn").value(true))
                .andExpect(jsonPath("$.email").value("ana@exemplo.com"));

        Map<String, Object> conta = admin().queryForMap(
                "SELECT * FROM fastpay.consumer_account WHERE google_subject = ?", s("sub-ana"));
        assertThat(conta).containsOnlyKeys("id", "google_subject", "email", "email_verified", "privacy_notice_version",
                "privacy_accepted_at", "created_at", "last_login_at");
        assertThat(conta.get("email")).isEqualTo("ana@exemplo.com");
        assertThat(conta.get("privacy_notice_version")).isEqualTo(AVISO);

        // Segundo login, em outro aparelho: entra direto, sem novo aviso nem nova conta.
        Browser celular = new Browser(mvc);
        googleDevolve(new GoogleIdentity(s("sub-ana"), "ana@exemplo.com", true));
        assertThat(location(callback(celular, iniciar(celular, "/minhas-comandas"), "codigo")))
                .isEqualTo("/minhas-comandas?google=conectado");
        assertThat(contas("sub-ana")).isEqualTo(1);
    }

    @Test
    void googleNegadoOuFalhoDeixaAvulsoEStateValeUmaVezPorDezMinutos() throws Exception {
        Browser cliente = new Browser(mvc);

        // Negou no Google.
        String negado = iniciar(cliente, "/minhas-comandas");
        assertThat(location(cliente.get("/api/v1/consumer/google/callback?state=" + negado + "&error=access_denied")))
                .isEqualTo("/minhas-comandas?google=recusado");

        // Retorno sem pedido, com state diferente ou repetido.
        assertThat(location(callback(cliente, "state-inventado", "codigo"))).endsWith("?google=falhou");
        String certo = iniciar(cliente, "/minhas-comandas");
        assertThat(location(callback(cliente, "outro-" + certo, "codigo"))).endsWith("?google=falhou");
        assertThat(location(callback(cliente, certo, "codigo"))).endsWith("?google=falhou");

        // Troca do código ou ID token inválido.
        doReturn(Optional.empty()).when(google).exchange(any(), any(), any());
        assertThat(location(callback(cliente, iniciar(cliente, "/minhas-comandas"), "codigo")))
                .endsWith("?google=falhou");

        // Pedido vencido (T-03: 10 min).
        googleDevolve(new GoogleIdentity(s("sub-lento"), "lento@exemplo.com", true));
        String lento = iniciar(cliente, "/minhas-comandas");
        relogio.advance(Duration.ofMinutes(10));
        assertThat(location(callback(cliente, lento, "codigo"))).endsWith("?google=falhou");

        // State de outro navegador não vale aqui (login CSRF).
        Browser atacante = new Browser(mvc);
        String doAtacante = iniciar(atacante, "/minhas-comandas");
        assertThat(location(callback(cliente, doAtacante, "codigo"))).endsWith("?google=falhou");

        cliente.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(false))
                .andExpect(jsonPath("$.pendingEmail").doesNotExist());
        assertThat(contas("sub-lento")).isZero();
    }

    @Test
    void avisoDePrivacidadeAguardaSoDezMinutos() throws Exception {
        Browser cliente = new Browser(mvc);
        googleDevolve(new GoogleIdentity(s("sub-espera"), "espera@exemplo.com", true));
        callback(cliente, iniciar(cliente, "/minhas-comandas"), "codigo");
        relogio.advance(Duration.ofMinutes(10));
        cliente.post("/api/v1/consumer/privacy/accept", "{\"noticeVersion\":\"" + AVISO + "\"}")
                .andExpect(status().isGone());
        assertThat(contas("sub-espera")).isZero();
    }

    @Test
    void loginsIniciadosPorOrigemTemLimite() throws Exception {
        Browser cliente = new Browser(mvc);
        for (int i = 0; i < 20; i++) {
            assertThat(location(cliente.get("/api/v1/consumer/google/start"))).startsWith("https://accounts.google.com/");
        }
        assertThat(location(cliente.get("/api/v1/consumer/google/start?returnTo=/minhas-comandas")))
                .isEqualTo("/minhas-comandas?google=limite");
        relogio.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(location(cliente.get("/api/v1/consumer/google/start"))).startsWith("https://accounts.google.com/");
    }

    @Test
    void contaGoogleComEmailDaEquipeNaoSeFundeComAEquipe() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        gabi.get("/api/v1/tabs").andExpect(status().isOk());

        // Mesmo navegador, mesmo e-mail da equipe: entra como consumidor e a sessão da equipe acaba.
        conectar(gabi, s("sub-gabi"), IdentityFixtures.email(garcom));
        gabi.get("/api/v1/tabs").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"));
        gabi.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(true));

        UUID conta = admin().queryForObject("SELECT id FROM fastpay.consumer_account WHERE google_subject = ?",
                UUID.class, s("sub-gabi"));
        assertThat(conta).isNotEqualTo(garcom);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_identity WHERE id = ?", Integer.class,
                conta)).isZero();

        // Entrar de novo na equipe encerra a sessão do consumidor.
        Browser equipe = login.inUnit(garcom, loja);
        equipe.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(false));
    }

    // Comanda no histórico

    @Test
    void loginGoogleSemAcessoAComandaNaoDaAcessoNenhum() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = abrir(gabi, "Ana");
        Browser cliente = new Browser(mvc);
        conectar(cliente, s("sub-sem-grant"));

        cliente.get("/api/v1/guest/tabs/" + comanda + "/account-link").andExpect(status().isNotFound());
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        cliente.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isNotFound());
        cliente.get("/api/v1/consumer/history").andExpect(jsonPath("$.total").value(0));
        assertThat(vinculos(comanda)).isZero();
    }

    @Test
    void vincularExigeLoginConfirmacaoEUmaContaPorComanda() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = abrir(gabi, "Ana");
        Browser cliente = new Browser(mvc);
        vincularAvulso(gabi, comanda, cliente);

        // Avulso sem login: vê que não está no histórico e não consegue guardar.
        cliente.get("/api/v1/guest/tabs/" + comanda + "/account-link").andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CONSUMIDOR_SEM_LOGIN"));

        conectar(cliente, s("sub-ana"));
        // O acesso avulso continua depois do login (cookie próprio).
        cliente.get("/api/v1/guest/tabs/" + comanda).andExpect(status().isOk());
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIRMACAO_NECESSARIA"));
        assertThat(vinculos(comanda)).isZero();

        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.mine").value(true));
        // Repetição não duplica.
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isOk());
        assertThat(vinculos(comanda)).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.audit_event WHERE subject_id = ?::uuid "
                + "AND action = 'consumidor.comanda_vinculada'", Integer.class, comanda)).isEqualTo(1);

        // Outra conta Google no mesmo aparelho (com o mesmo acesso): recusada, a comanda continua da primeira.
        cliente.post("/api/v1/consumer/logout", "{}").andExpect(status().isNoContent());
        conectar(cliente, s("sub-bia"));
        cliente.get("/api/v1/guest/tabs/" + comanda + "/account-link").andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.mine").value(false));
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_DE_OUTRA_CONTA"));
        cliente.get("/api/v1/consumer/history").andExpect(jsonPath("$.total").value(0));
        assertThat(vinculos(comanda)).isEqualTo(1);
    }

    @Test
    void acessoRecuperadoPelaEquipeRevogaOAnteriorParaVincular() throws Exception {
        Browser gabi = login.inUnit(gerente, loja);
        String comanda = abrir(gabi, "Ana");
        Browser antigo = new Browser(mvc);
        vincularAvulso(gabi, comanda, antigo);
        gabi.post("/api/v1/tabs/" + comanda + "/access-recovery",
                "{\"nameChecked\":true,\"accountCheck\":\"HORA_DA_ABERTURA\",\"reason\":\"Trocou de celular\"}")
                .andExpect(status().isOk());

        conectar(antigo, s("sub-antigo"));
        antigo.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isNotFound());
        assertThat(vinculos(comanda)).isZero();
    }

    @Test
    void historicoMostraComandasDaPessoaEmTodasAsLojasSemExporTerceiros() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        Browser ana = new Browser(mvc);
        Browser bia = new Browser(mvc);

        String primeira = abrir(gabi, "Ana");
        vincularAvulso(gabi, primeira, ana);
        relogio.advance(Duration.ofMinutes(1));
        String naOutra = abrir(otto, "Aninha");
        vincularAvulso(otto, naOutra, ana);
        relogio.advance(Duration.ofMinutes(1));
        String daBia = abrir(gabi, "Bia");
        vincularAvulso(gabi, daBia, bia);
        String semLogin = abrir(gabi, "Carlos");

        conectar(ana, s("sub-ana"));
        guardar(ana, primeira);
        guardar(ana, naOutra);
        conectar(bia, s("sub-bia"));
        guardar(bia, daBia);

        // Comanda encerrada ainda entra enquanto o acesso vale (FR-10, "inclusive no final do atendimento").
        admin().update("UPDATE fastpay.tab SET closed_at = now(), access_until = now() + interval '7 days' WHERE id = ?",
                UUID.fromString(primeira));

        ana.get("/api/v1/consumer/history").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].tabId").value(naOutra))
                .andExpect(jsonPath("$.items[0].establishmentName").value("Outro Bar"))
                .andExpect(jsonPath("$.items[0].name").value("Aninha"))
                .andExpect(jsonPath("$.items[1].tabId").value(primeira))
                .andExpect(jsonPath("$.items[1].establishmentName").value("Bar do Histórico"))
                .andExpect(jsonPath("$.items[1].closedAt").isNotEmpty());
        ana.get("/api/v1/consumer/history?size=1&page=1").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].tabId").value(primeira));
        ana.get("/api/v1/consumer/history?size=500").andExpect(jsonPath("$.size").value(100));
        bia.get("/api/v1/consumer/history").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].tabId").value(daBia));

        // A loja vê só a marca nas comandas dela; a outra loja não vê comanda alheia.
        gabi.get("/api/v1/tabs/" + primeira + "/consumer-account").andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
        gabi.get("/api/v1/tabs/" + semLogin + "/consumer-account").andExpect(jsonPath("$.registered").value(false));
        otto.get("/api/v1/tabs/" + primeira + "/consumer-account").andExpect(status().isNotFound());
    }

    @Test
    void rlsSeparaContasVinculosELojas() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser otto = login.inUnit(garcomDaOutra, outraLoja);
        Browser ana = new Browser(mvc);
        String daAna = abrir(gabi, "Ana");
        vincularAvulso(gabi, daAna, ana);
        conectar(ana, s("sub-ana"));
        guardar(ana, daAna);
        String outraNaLoja = abrir(gabi, "Zé");
        abrir(otto, "Otto");
        UUID contaAna = admin().queryForObject("SELECT id FROM fastpay.consumer_account WHERE google_subject = ?",
                UUID.class, s("sub-ana"));
        conectar(new Browser(mvc), s("sub-bia"));
        UUID contaBia = admin().queryForObject("SELECT id FROM fastpay.consumer_account WHERE google_subject = ?",
                UUID.class, s("sub-bia"));

        // A loja não lê contas de consumidor (nem o e-mail).
        assertThat(tenants.inEstablishment(loja, () -> count("consumer_account"))).isZero();
        assertThat(tenants.inEstablishment(loja, () -> count("consumer_tab_link"))).isEqualTo(1);
        assertThat(tenants.inEstablishment(outraLoja, () -> count("consumer_tab_link"))).isZero();
        // A pessoa lê só a própria conta, os próprios vínculos e as comandas vinculadas por ela.
        assertThat(tenants.asConsumer(contaAna, () -> count("consumer_account"))).isEqualTo(1);
        assertThat(tenants.asConsumer(contaAna, () -> count("tab"))).isEqualTo(1);
        assertThat(tenants.asConsumer(contaAna, () -> count("establishment"))).isEqualTo(1);
        assertThat(tenants.asConsumer(contaAna, () -> count("guest_grant"))).isZero();
        assertThat(tenants.asConsumer(contaBia, () -> count("consumer_tab_link"))).isZero();
        assertThat(tenants.asConsumer(contaBia, () -> count("tab"))).isZero();
        // Ninguém grava vínculo para outra conta ou em comanda de outra loja.
        assertThat(insercaoRecusada(() -> tenants.inEstablishmentAsConsumer(outraLoja, contaBia, () -> runtime.update("""
                INSERT INTO consumer_tab_link (id, consumer_account_id, establishment_id, tab_id, grant_id, linked_at)
                SELECT gen_random_uuid(), ?, ?, ?, gen_random_uuid(), now()
                """, contaAna, outraLoja, UUID.fromString(outraNaLoja))))).isTrue();
    }

    @Test
    void sessaoDoConsumidorVence7DiasParadaE30DiasNoTotal() throws Exception {
        Browser cliente = new Browser(mvc);
        conectar(cliente, s("sub-prazo"));

        // Polling/SSE não contam como ação: 7 dias só com tráfego automático derruba.
        relogio.advance(Duration.ofDays(6));
        cliente.background("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(true));
        relogio.advance(Duration.ofDays(1));
        cliente.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(false));

        // Com uso a cada 6 dias, vale até 30 dias do login.
        Browser assiduo = new Browser(mvc);
        conectar(assiduo, s("sub-assiduo"));
        for (int i = 0; i < 4; i++) {
            relogio.advance(Duration.ofDays(6));
            assiduo.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(true));
        }
        relogio.advance(Duration.ofDays(6));
        assiduo.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(false));
    }

    // Apoio

    /** "sub" do Google único por teste: o banco é compartilhado entre os testes da execução. */
    private String s(String sub) {
        return sub + "-" + rodada;
    }

    private void googleDevolve(GoogleIdentity identidade) {
        doReturn(Optional.of(identidade)).when(google).exchange(eq("codigo"), any(), any());
    }

    /** Login Google completo de quem ainda não tem conta: Google, aviso aceito, sessão aberta. */
    private void conectar(Browser navegador, String sub) throws Exception {
        conectar(navegador, sub, sub + "@exemplo.com");
    }

    private void conectar(Browser navegador, String sub, String email) throws Exception {
        googleDevolve(new GoogleIdentity(sub, email, true));
        String destino = location(callback(navegador, iniciar(navegador, "/minhas-comandas"), "codigo"));
        if (destino.startsWith("/privacidade-google")) {
            navegador.post("/api/v1/consumer/privacy/accept", "{\"noticeVersion\":\"" + AVISO + "\"}")
                    .andExpect(status().isOk());
        }
        navegador.get("/api/v1/consumer/session").andExpect(jsonPath("$.signedIn").value(true));
    }

    private String iniciar(Browser navegador, String retorno) throws Exception {
        String destino = location(navegador.get("/api/v1/consumer/google/start?returnTo=" + retorno)
                .andExpect(status().isFound()));
        return UriComponentsBuilder.fromUriString(destino).build().getQueryParams().getFirst("state");
    }

    private static ResultActions callback(Browser navegador, String state, String code) throws Exception {
        return navegador.get("/api/v1/consumer/google/callback?state=" + state + "&code=" + code)
                .andExpect(status().isFound());
    }

    private static String location(ResultActions result) {
        return result.andReturn().getResponse().getHeader("Location");
    }

    private String abrir(Browser equipe, String nome) throws Exception {
        return JsonPath.read(equipe.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void vincularAvulso(Browser equipe, String comanda, Browser cliente) throws Exception {
        String link = JsonPath.read(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.link");
        String token = link.substring(link.indexOf('#') + 1);
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + token + "\"}").andExpect(status().isOk());
    }

    private void guardar(Browser cliente, String comanda) throws Exception {
        cliente.post("/api/v1/guest/tabs/" + comanda + "/account-link", "{\"confirmed\":true}")
                .andExpect(status().isOk());
    }

    private int contas(String sub) {
        return admin().queryForObject("SELECT count(*) FROM fastpay.consumer_account WHERE google_subject = ?",
                Integer.class, s(sub));
    }

    private int vinculos(String comanda) {
        return admin().queryForObject("SELECT count(*) FROM fastpay.consumer_tab_link WHERE tab_id = ?::uuid",
                Integer.class, comanda);
    }

    private long count(String tabela) {
        return runtime.queryForObject("SELECT count(*) FROM " + tabela, Long.class);
    }

    private static boolean insercaoRecusada(Runnable insercao) {
        try {
            insercao.run();
            return false;
        } catch (DataAccessException recusada) {
            return true;
        }
    }
}
