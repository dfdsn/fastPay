package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.application.PasswordResets;
import tech.malyah.fastpay.identity.application.PasswordResets.ConfirmOutcome;
import tech.malyah.fastpay.identity.application.PasswordResets.PasswordChanged;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.mail.infrastructure.TestMailbox;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Login da equipe, sessão e redefinição de senha pela API (H08, FR-04, NFR-04, especificação §5), com
 * PostgreSQL real, Spring Session JDBC, CSRF e relógio controlado: expiração por inatividade e absoluta
 * por perfil, polling sem renovar, fixação de sessão, tentativas abusivas, mensagens que não revelam
 * contas e link de redefinição repetido, vencido, substituído ou concorrente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.RelogioControlado.class)
class AuthIntegrationTest extends PostgresIntegrationTest {

    static final String SENHA = "uma senha longa de teste";
    static final String NOVA_SENHA = "outra frase bem comprida";
    static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    @TestConfiguration
    static class RelogioControlado {
        @Bean
        @Primary
        AdjustableClock relogio() {
            return new AdjustableClock(Instant.now());
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    TestMailbox caixa;

    @Autowired
    JobWorker worker;

    @Autowired
    PasswordResets resets;

    @Autowired
    MfaSecretCipher cifra;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    MfaFixtures mfa;

    UUID loja;
    UUID outraLoja;

    @BeforeEach
    void lojas() {
        mfa = new MfaFixtures(admin(), cifra);
        loja = fixtures.establishment("Bar da Praia");
        outraLoja = fixtures.establishment("Adega Centro");
    }

    // --- Login e sessão ---

    @Test
    void loginGuardaSessaoNoBancoComCookieHttpOnlySeguroELax() throws Exception {
        UUID gil = pessoa("Gil", MembershipRole.GARCOM);
        Browser navegador = new Browser(mvc);

        ResultActions login = navegador.login(IdentityFixtures.email(gil), SENHA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Gil"))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(3600));

        List<String> cookies = login.andReturn().getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).filteredOn(c -> c.startsWith("FASTPAY_SESSION="))
                .singleElement().satisfies(c -> assertThat(c).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.spring_session WHERE principal_name = ?",
                Integer.class, gil.toString())).isEqualTo(1);
        assertThat(String.join(" ", cookies)).doesNotContain(SENHA);
        navegador.get("/api/v1/auth/session").andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Gil"));
    }

    @Test
    void loginDescartaSessaoAnteriorEEmiteNovoId() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        UUID bruno = pessoa("Bruno", MembershipRole.GARCOM);
        Browser atacante = entrar(bruno);
        Browser vitima = new Browser(mvc);
        // Cookie de sessão plantado no navegador da vítima.
        atacante.copyCookiesTo(vitima);
        String plantado = vitima.cookie("FASTPAY_SESSION").orElseThrow();

        vitima.login(IdentityFixtures.email(ana), SENHA).andExpect(status().isOk());

        assertThat(vitima.cookie("FASTPAY_SESSION")).hasValueSatisfying(id -> assertThat(id).isNotEqualTo(plantado));
        vitima.get("/api/v1/auth/session").andExpect(jsonPath("$.displayName").value("Ana"));
        atacante.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void senhaErradaContaInexistenteOuSemSenhaTemAMesmaResposta() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        UUID semSenha = fixtures.identity("Sem senha");

        String errada = new Browser(mvc).login(IdentityFixtures.email(ana), "senha errada demais")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CREDENCIAIS_INVALIDAS"))
                .andExpect(jsonPath("$.detail").value("E-mail ou senha incorretos."))
                .andReturn().getResponse().getContentAsString();
        String inexistente = new Browser(mvc).login("ninguem-" + UUID.randomUUID() + "@teste.invalid", SENHA)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String contaSemSenha = new Browser(mvc).login(IdentityFixtures.email(semSenha), SENHA)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(inexistente).isEqualTo(errada);
        assertThat(contaSemSenha).isEqualTo(errada);
    }

    @Test
    void emailComMaiusculasEEspacosEhNormalizado() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);

        new Browser(mvc).login("  " + IdentityFixtures.email(ana).toUpperCase() + " ", SENHA).andExpect(status().isOk());
    }

    @Test
    void loginSemTokenCsrfOuComTokenErradoEhRecusado() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String corpo = credenciais(IdentityFixtures.email(ana), SENHA);
        Browser navegador = new Browser(mvc);

        navegador.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAGINA_DESATUALIZADA"))
                .andExpect(jsonPath("$.detail").value("Sua página está desatualizada. Recarregue e tente novamente."));
        navegador.get("/api/v1/platform/info");
        navegador.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo)
                        .header("X-XSRF-TOKEN", "forjado"))
                .andExpect(status().isForbidden());
        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEncerraASessaoNoServidor() throws Exception {
        Browser navegador = entrar(pessoa("Ana", MembershipRole.GERENTE));
        Browser copia = new Browser(mvc);
        navegador.copyCookiesTo(copia);

        navegador.post("/api/v1/auth/logout", "").andExpect(status().isNoContent());

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"));
        copia.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    // --- Expiração por perfil ---

    @Test
    void gerenteExpiraApos30MinutosSemAtividade() throws Exception {
        Browser navegador = entrar(pessoa("Gerson", MembershipRole.GERENTE));

        relogio.advance(Duration.ofMinutes(29));
        navegador.get("/api/v1/auth/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(1800));
        relogio.advance(Duration.ofMinutes(29));
        navegador.get("/api/v1/auth/session").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(30));

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        navegador.get("/api/v1/session/units").andExpect(status().isUnauthorized());
    }

    @Test
    void garcomTemUmaHoraDeInatividade() throws Exception {
        Browser navegador = entrar(pessoa("Gil", MembershipRole.GARCOM));

        relogio.advance(Duration.ofMinutes(59));
        navegador.get("/api/v1/auth/session").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(60));

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void adminFastPayTemQuinzeMinutosEOitoHorasEPrevaleceSobreVinculos() throws Exception {
        UUID admin = pessoa("Admin", MembershipRole.GARCOM);
        admin().update("UPDATE fastpay.user_identity SET platform_role = 'ADMIN_FASTPAY' WHERE id = ?", admin);
        Instant inicio = relogio.instant();

        mfa.enroll(admin);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(admin), SENHA).andExpect(status().isOk());
        navegador.verify(mfa.nextCode(admin, relogio.instant())).andExpect(status().isOk())
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(900))
                .andExpect(jsonPath("$.absoluteExpiresAt").value(inicio.plus(Duration.ofHours(8)).toString()));
        relogio.advance(Duration.ofMinutes(15));

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void perfilMaisRestritivoEntreAsUnidadesDefineOPrazo() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GARCOM);
        fixtures.membership(ana, outraLoja, MembershipRole.ADMIN_ESTABELECIMENTO);
        mfa.enroll(ana);

        entrar(ana).get("/api/v1/auth/session").andExpect(jsonPath("$.idleTimeoutSeconds").value(1800));
    }

    @Test
    void pollingNaoRenovaAInatividade() throws Exception {
        Browser navegador = entrar(pessoa("Gerson", MembershipRole.GERENTE));

        relogio.advance(Duration.ofMinutes(20));
        navegador.background("/api/v1/auth/session").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(9));
        navegador.background("/api/v1/session/units").andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(1));

        navegador.background("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void atividadeConstanteNaoPassaDoLimiteAbsoluto() throws Exception {
        Instant inicio = relogio.instant();
        Browser navegador = entrar(pessoa("Gil", MembershipRole.GARCOM));
        navegador.get("/api/v1/auth/session")
                .andExpect(jsonPath("$.absoluteExpiresAt").value(inicio.plus(Duration.ofHours(12)).toString()));

        for (int i = 0; i < 13; i++) {
            relogio.advance(Duration.ofMinutes(55));
            navegador.get("/api/v1/auth/session").andExpect(status().isOk());
        }
        relogio.advance(Duration.ofMinutes(5));

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
    }

    @Test
    void trocarDeUnidadeNaoEstendeOInicioAbsoluto() throws Exception {
        Instant inicio = relogio.instant();
        Browser navegador = entrar(pessoa("Gil", MembershipRole.GARCOM));
        relogio.advance(Duration.ofMinutes(30));

        navegador.put("/api/v1/session/unit", "{\"establishmentId\":\"" + loja + "\"}").andExpect(status().isOk());

        navegador.get("/api/v1/auth/session")
                .andExpect(jsonPath("$.absoluteExpiresAt").value(inicio.plus(Duration.ofHours(12)).toString()));
    }

    // --- Tentativas abusivas ---

    @Test
    void cincoFalhasBloqueiamAContaMesmoComASenhaCertaAteAJanelaPassar() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String email = IdentityFixtures.email(ana);
        for (int i = 0; i < 5; i++) {
            new Browser(mvc).login(email, "senha errada " + i).andExpect(status().isUnauthorized());
        }

        new Browser(mvc).login(email, SENHA).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MUITAS_TENTATIVAS"))
                .andExpect(jsonPath("$.detail").value("Muitas tentativas. Aguarde alguns minutos e tente novamente."));

        relogio.advance(Duration.ofMinutes(15));
        new Browser(mvc).login(email, SENHA).andExpect(status().isOk());
    }

    @Test
    void contaInexistenteTambemEhBloqueadaParaNaoRevelarExistencia() throws Exception {
        String email = "ninguem-" + UUID.randomUUID() + "@teste.invalid";
        for (int i = 0; i < 5; i++) {
            new Browser(mvc).login(email, "qualquer senha " + i).andExpect(status().isUnauthorized());
        }

        new Browser(mvc).login(email, SENHA).andExpect(status().isTooManyRequests());
    }

    @Test
    void loginCertoZeraAsFalhasDaConta() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String email = IdentityFixtures.email(ana);
        for (int i = 0; i < 4; i++) {
            new Browser(mvc).login(email, "senha errada " + i).andExpect(status().isUnauthorized());
        }
        new Browser(mvc).login(email, SENHA).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            new Browser(mvc).login(email, "senha errada " + i).andExpect(status().isUnauthorized());
        }

        new Browser(mvc).login(email, SENHA).andExpect(status().isOk());
    }

    @Test
    void muitasFalhasDaMesmaOrigemBloqueiamQualquerContaNelaMasNaoEmOutra() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser origem = new Browser(mvc);
        for (int i = 0; i < 20; i++) {
            origem.login("tentativa-" + i + "-" + UUID.randomUUID() + "@teste.invalid", SENHA)
                    .andExpect(status().isUnauthorized());
        }

        origem.login(IdentityFixtures.email(ana), SENHA).andExpect(status().isTooManyRequests());
        new Browser(mvc).login(IdentityFixtures.email(ana), SENHA).andExpect(status().isOk());
    }

    // --- Redefinição de senha ---

    @Test
    void redefinirSenhaPeloLinkTrocaASenha() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String email = IdentityFixtures.email(ana);

        new Browser(mvc).post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.detail").value(AuthController.RESET_REQUESTED));
        TestMailbox.Message mensagem = entregues().latestTo(email).orElseThrow();
        assertThat(mensagem.body()).contains("Olá, Ana", "30 minutos", "http://127.0.0.1:8088/redefinir-senha#token=");
        assertThat(admin().queryForList("SELECT token_hash FROM fastpay.password_reset_token WHERE identity_id = ?",
                String.class, ana)).singleElement().satisfies(hash -> assertThat(mensagem.body()).doesNotContain(hash));

        confirmar(token(mensagem), NOVA_SENHA).andExpect(status().isNoContent());

        new Browser(mvc).login(email, SENHA).andExpect(status().isUnauthorized());
        new Browser(mvc).login(email, NOVA_SENHA).andExpect(status().isOk());
    }

    @Test
    void pedidoParaEmailInexistenteTemAMesmaRespostaENaoEnviaNada() throws Exception {
        String email = "ninguem-" + UUID.randomUUID() + "@teste.invalid";

        new Browser(mvc).post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.detail").value(AuthController.RESET_REQUESTED));

        assertThat(entregues().latestTo(email)).isEmpty();
    }

    @Test
    void linkSoPodeSerUsadoUmaVez() throws Exception {
        String token = pedirLink(pessoa("Ana", MembershipRole.GERENTE));
        confirmar(token, NOVA_SENHA).andExpect(status().isNoContent());

        confirmar(token, "mais uma frase comprida").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("Este link de redefinição não vale mais. Peça um novo."));
    }

    @Test
    void linkVenceEmTrintaMinutos() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String token = pedirLink(ana);

        relogio.advance(Duration.ofMinutes(30));

        confirmar(token, NOVA_SENHA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINK_INVALIDO"));
        new Browser(mvc).login(IdentityFixtures.email(ana), SENHA).andExpect(status().isOk());
    }

    @Test
    void novoPedidoInvalidaOLinkAnterior() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String primeiro = pedirLink(ana);
        String segundo = pedirLink(ana);

        confirmar(primeiro, NOVA_SENHA).andExpect(status().isBadRequest());
        confirmar(segundo, NOVA_SENHA).andExpect(status().isNoContent());
    }

    @Test
    void redefinirEncerraTodasAsSessoesDaPessoaESoDela() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser celular = entrar(ana);
        Browser computador = entrar(ana);
        Browser colega = entrar(pessoa("Bruno", MembershipRole.GARCOM));

        confirmar(pedirLink(ana), NOVA_SENHA).andExpect(status().isNoContent());

        celular.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        computador.get("/api/v1/session/units").andExpect(status().isUnauthorized());
        colega.get("/api/v1/auth/session").andExpect(status().isOk());
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.spring_session WHERE principal_name = ?",
                Integer.class, ana.toString())).isZero();
    }

    @Test
    void senhaRecusadaPelaPoliticaMantemOLinkValido() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String token = pedirLink(ana);

        confirmar(token, "curta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SENHA_RECUSADA"))
                .andExpect(jsonPath("$.detail").value("Use pelo menos 12 caracteres."));
        confirmar(token, "123456789012").andExpect(jsonPath("$.detail").value("Esta senha é muito comum. Escolha outra."));
        confirmar(token, "minha " + ana).andExpect(jsonPath("$.detail").value("Não use seu e-mail na senha."));

        confirmar(token, NOVA_SENHA).andExpect(status().isNoContent());
    }

    @Test
    void identidadeSemSenhaDefinePelaPrimeiraVezPeloLink() throws Exception {
        UUID semSenha = fixtures.identity("Nova");
        fixtures.membership(semSenha, loja, MembershipRole.GARCOM);

        confirmar(pedirLink(semSenha), NOVA_SENHA).andExpect(status().isNoContent());

        new Browser(mvc).login(IdentityFixtures.email(semSenha), NOVA_SENHA).andExpect(status().isOk());
    }

    @Test
    void pedidosRepetidosDaMesmaContaParamDeEnviarSemMudarAResposta() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String email = IdentityFixtures.email(ana);
        for (int i = 0; i < 3; i++) {
            pedirLink(ana);
        }
        caixa.clear();

        new Browser(mvc).post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted());

        assertThat(entregues().latestTo(email)).isEmpty();
        relogio.advance(Duration.ofHours(1));
        pedirLink(ana);
    }

    @Test
    void linksInvalidosRepetidosDaMesmaOrigemSaoBloqueados() throws Exception {
        Browser origem = new Browser(mvc);
        for (int i = 0; i < 10; i++) {
            origem.post("/api/v1/auth/password-reset/confirm", confirmacao("token-inventado-" + i, NOVA_SENHA))
                    .andExpect(status().isBadRequest());
        }

        origem.post("/api/v1/auth/password-reset/confirm", confirmacao("token-inventado", NOVA_SENHA))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void duasConfirmacoesSimultaneasDoMesmoLinkSoUmaTrocaASenha() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        String token = pedirLink(ana);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<ConfirmOutcome> confirmar = () -> {
            largada.await();
            return resets.confirm(token, NOVA_SENHA, "10.255.255." + Thread.currentThread().threadId() % 200);
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ConfirmOutcome> a = pool.submit(confirmar);
            Future<ConfirmOutcome> b = pool.submit(confirmar);
            largada.countDown();

            assertThat(List.of(a.get(), b.get())).filteredOn(PasswordChanged.class::isInstance).hasSize(1);
        }
    }

    private UUID pessoa(String nome, MembershipRole papel) {
        UUID id = fixtures.identity(nome);
        fixtures.credential(id, SENHA);
        fixtures.membership(id, loja, papel);
        if (papel == MembershipRole.GERENTE || papel == MembershipRole.ADMIN_ESTABELECIMENTO) {
            // MFA obrigatório para esses papéis (H09).
            mfa.enroll(id);
        }
        return id;
    }

    private Browser entrar(UUID identidade) throws Exception {
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(identidade), SENHA).andExpect(status().isOk());
        if (mfa.enrolled(identidade)) {
            navegador.verify(mfa.nextCode(identidade, relogio.instant())).andExpect(status().isOk());
        }
        return navegador;
    }

    private String pedirLink(UUID identidade) throws Exception {
        String email = IdentityFixtures.email(identidade);
        caixa.clear();
        new Browser(mvc).post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted());
        return token(entregues().latestTo(email).orElseThrow());
    }

    private ResultActions confirmar(String token, String senha) throws Exception {
        return new Browser(mvc).post("/api/v1/auth/password-reset/confirm", confirmacao(token, senha));
    }

    private static String token(TestMailbox.Message mensagem) {
        Matcher matcher = TOKEN.matcher(mensagem.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String credenciais(String email, String senha) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, senha);
    }

    private static String confirmacao(String token, String senha) {
        return "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, senha);
    }

    /** Executa os e-mails na fila (o worker periódico fica desligado nos testes) e devolve a caixa de teste. */
    private TestMailbox entregues() {
        while (worker.runOnce() > 0) {
            // até não haver mais jobs vencidos
        }
        return caixa;
    }
}
