package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.domain.Base32;
import tech.malyah.fastpay.identity.domain.Totp;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.infrastructure.TestMailbox;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Verificação em duas etapas pela API (H09, FR-05, especificação §5), com PostgreSQL real, Spring Session
 * JDBC, CSRF e relógio controlado: cadastro obrigatório antes de qualquer função, código repetido e
 * janela, códigos simultâneos, códigos de recuperação de uso único levando só ao recadastro, redefinição
 * de senha sem remover MFA, promoção durante a sessão e MFA opcional de garçom.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MfaIntegrationTest.RelogioControlado.class)
class MfaIntegrationTest extends PostgresIntegrationTest {

    static final String SENHA = "uma senha longa de teste";

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
    MfaSecretCipher cifra;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    MfaFixtures mfa;
    UUID loja;

    @BeforeEach
    void loja() {
        mfa = new MfaFixtures(admin(), cifra);
        loja = fixtures.establishment("Bar da Praia");
    }

    // --- Cadastro obrigatório ---

    @Test
    void gerenteSemAutenticadorPrecisaCadastrarAntesDeQualquerFuncao() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);

        navegador.login(IdentityFixtures.email(ana), SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"))
                .andExpect(jsonPath("$.displayName").value("Ana"))
                .andExpect(jsonPath("$.idleExpiresAt").doesNotExist());
        navegador.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_CADASTRO_OBRIGATORIO"))
                .andExpect(jsonPath("$.detail").value("Configure a verificação em duas etapas para continuar."));
        navegador.get("/api/v1/auth/session").andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"));
        navegador.verify("123456").andExpect(status().isForbidden());

        byte[] segredo = iniciarCadastro(navegador);
        navegador.post("/api/v1/auth/mfa/setup/confirm", codigo("000000")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODIGO_INVALIDO"));
        String anterior = navegador.cookie("FASTPAY_SESSION").orElseThrow();
        String resposta = navegador.post("/api/v1/auth/mfa/setup/confirm", codigo(codigoAgora(segredo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andExpect(jsonPath("$.session.stage").value("AUTENTICADO"))
                .andExpect(jsonPath("$.session.idleTimeoutSeconds").value(1800))
                .andReturn().getResponse().getContentAsString();

        assertThat(navegador.cookie("FASTPAY_SESSION")).hasValueSatisfying(id -> assertThat(id).isNotEqualTo(anterior));
        navegador.get("/api/v1/session/units").andExpect(status().isOk());
        List<String> codigos = JsonPath.read(resposta, "$.recoveryCodes");
        assertThat(codigos).doesNotHaveDuplicates().allMatch(c -> c.matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}"));
        // Banco: segredo cifrado, códigos só como hash, trilha registrada.
        String cifrado = admin().queryForObject("SELECT secret_ciphertext FROM fastpay.user_mfa WHERE identity_id = ?",
                String.class, ana);
        assertThat(cifrado).startsWith("v1:").doesNotContain(Base32.encode(segredo));
        List<String> hashes = admin().queryForList("SELECT code_hash FROM fastpay.mfa_recovery_code WHERE identity_id = ?",
                String.class, ana);
        assertThat(hashes).hasSize(10).noneMatch(h -> codigos.stream().anyMatch(c -> h.contains(c.replace("-", ""))));
        assertThat(eventos(ana)).containsExactly("mfa.cadastrado");
    }

    @Test
    void codigoDeConfirmacaoDoCadastroNaoServeDeNovoNoLogin() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(ana), SENHA);
        byte[] segredo = iniciarCadastro(navegador);
        String usado = codigoAgora(segredo);
        navegador.post("/api/v1/auth/mfa/setup/confirm", codigo(usado)).andExpect(status().isOk());

        Browser outro = new Browser(mvc);
        outro.login(IdentityFixtures.email(ana), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        outro.verify(usado).andExpect(status().isBadRequest());
    }

    @Test
    void adminFastPaySemVinculoTambemPrecisaDeMfa() throws Exception {
        UUID admin = fixtures.identity("Admin", "ADMIN_FASTPAY");
        fixtures.credential(admin, SENHA);

        new Browser(mvc).login(IdentityFixtures.email(admin), SENHA)
                .andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"));
    }

    @Test
    void etapaDoMfaVenceEmDezMinutos() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(ana), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));

        relogio.advance(Duration.ofMinutes(10));

        navegador.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        navegador.verify(mfa.nextCode(ana, relogio.instant())).andExpect(status().isUnauthorized());
    }

    // --- Login com autenticador ---

    @Test
    void loginComAutenticadorPedeOCodigoETrocaASessao() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);

        navegador.login(IdentityFixtures.email(ana), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        navegador.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_PENDENTE"));
        navegador.post("/api/v1/auth/mfa/setup", "").andExpect(status().isForbidden());
        String pendente = navegador.cookie("FASTPAY_SESSION").orElseThrow();

        navegador.verify(mfa.nextCode(ana, relogio.instant())).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("AUTENTICADO"))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(1800));

        assertThat(navegador.cookie("FASTPAY_SESSION")).hasValueSatisfying(id -> assertThat(id).isNotEqualTo(pendente));
        navegador.get("/api/v1/session/units").andExpect(status().isOk());
        navegador.verify("123456").andExpect(status().isConflict());
    }

    @Test
    void mesmoCodigoNaoValeDuasVezesNemDentroDaJanela() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        String codigo = mfa.nextCode(ana, relogio.instant());
        entrarCom(ana, codigo).andExpect(status().isOk());

        entrarCom(ana, codigo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODIGO_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("Código incorreto ou já usado. Confira o aplicativo e tente de novo."));
    }

    @Test
    void janelaAceitaUmPassoDeCadaLadoENaoMais() throws Exception {
        byte[] segredo;
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        segredo = mfa.enroll(ana);
        long passo = Totp.step(relogio.instant());

        entrarCom(ana, Totp.code(segredo, passo - 2)).andExpect(status().isBadRequest());
        entrarCom(ana, Totp.code(segredo, passo + 2)).andExpect(status().isBadRequest());
        entrarCom(ana, Totp.code(segredo, passo - 1)).andExpect(status().isOk());
        entrarCom(ana, Totp.code(segredo, passo + 1)).andExpect(status().isOk());
        // Passo anterior ao último aceito também não vale mais.
        entrarCom(ana, Totp.code(segredo, passo)).andExpect(status().isBadRequest());
    }

    @Test
    void duasVerificacoesSimultaneasComOMesmoCodigoSoUmaEntra() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        List<Browser> navegadores = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Browser navegador = new Browser(mvc);
            navegador.login(IdentityFixtures.email(ana), SENHA).andExpect(status().isOk());
            navegadores.add(navegador);
        }
        String codigo = mfa.nextCode(ana, relogio.instant());
        CountDownLatch largada = new CountDownLatch(1);
        List<Callable<Integer>> tentativas = navegadores.stream().<Callable<Integer>>map(n -> () -> {
            largada.await();
            return n.verify(codigo).andReturn().getResponse().getStatus();
        }).toList();

        List<Integer> resultados = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(tentativas.size())) {
            List<Future<Integer>> futuros = tentativas.stream().map(executor::submit).toList();
            largada.countDown();
            for (Future<Integer> futuro : futuros) {
                resultados.add(futuro.get());
            }
        }

        assertThat(resultados).filteredOn(s -> s == 200).hasSize(1);
        assertThat(resultados).filteredOn(s -> s == 400).hasSize(3);
    }

    @Test
    void codigosErradosDemaisBloqueiamAteAJanelaPassar() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        for (int i = 0; i < 5; i++) {
            entrarCom(ana, "00000" + i).andExpect(status().isBadRequest());
        }

        entrarCom(ana, mfa.nextCode(ana, relogio.instant())).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MUITAS_TENTATIVAS"));

        relogio.advance(Duration.ofMinutes(15));
        entrarCom(ana, mfa.nextCode(ana, relogio.instant())).andExpect(status().isOk());
    }

    // --- Códigos de recuperação ---

    @Test
    void codigoDeRecuperacaoSoAbreORecadastroQueTrocaTudoEncerraSessoesEAvisa() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser celular = new Browser(mvc);
        celular.login(IdentityFixtures.email(ana), SENHA);
        byte[] antigo = iniciarCadastro(celular);
        List<String> codigos = JsonPath.read(celular.post("/api/v1/auth/mfa/setup/confirm", codigo(codigoAgora(antigo)))
                .andReturn().getResponse().getContentAsString(), "$.recoveryCodes");
        mfa.remember(ana, antigo);
        relogio.advance(Duration.ofSeconds(30));
        caixa.clear();

        // Celular perdido: entra no computador com senha e um código de recuperação.
        Browser computador = new Browser(mvc);
        computador.login(IdentityFixtures.email(ana), SENHA);
        computador.verify(codigos.get(3).toUpperCase().replace("-", " ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("MFA_RECADASTRO"))
                .andExpect(jsonPath("$.idleExpiresAt").doesNotExist());
        computador.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_RECADASTRO"));
        computador.verify(mfa.nextCode(ana, relogio.instant())).andExpect(status().isForbidden());

        byte[] novo = iniciarCadastro(computador);
        List<String> novosCodigos = JsonPath.read(computador.post("/api/v1/auth/mfa/setup/confirm",
                codigo(codigoAgora(novo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.stage").value("AUTENTICADO"))
                .andReturn().getResponse().getContentAsString(), "$.recoveryCodes");

        computador.get("/api/v1/session/units").andExpect(status().isOk());
        celular.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        assertThat(novosCodigos).doesNotContainAnyElementsOf(codigos);
        assertThat(caixa.latestTo(IdentityFixtures.email(ana))).hasValueSatisfying(m -> {
            assertThat(m.subject()).isEqualTo("Verificação em duas etapas reconfigurada no fastPay");
            assertThat(m.body()).contains("Olá, Ana", "deixaram de valer", "sessões abertas");
        });
        assertThat(eventos(ana)).containsExactly("mfa.cadastrado", "mfa.codigo_recuperacao_usado", "mfa.recadastrado");

        relogio.advance(Duration.ofSeconds(30));
        // Autenticador e códigos antigos não valem mais; o novo vale.
        entrarCom(ana, Totp.code(antigo, Totp.step(relogio.instant()))).andExpect(status().isBadRequest());
        entrarCom(ana, codigos.get(5)).andExpect(status().isBadRequest());
        entrarCom(ana, Totp.code(novo, Totp.step(relogio.instant()))).andExpect(status().isOk());
    }

    @Test
    void codigoDeRecuperacaoEhDeUsoUnicoEDeUmaSoPessoa() throws Exception {
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        UUID bruno = comAutenticador("Bruno", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(ana), SENHA);
        byte[] segredo = iniciarCadastro(navegador);
        List<String> codigos = JsonPath.read(navegador.post("/api/v1/auth/mfa/setup/confirm",
                codigo(codigoAgora(segredo))).andReturn().getResponse().getContentAsString(), "$.recoveryCodes");

        entrarCom(bruno, codigos.get(0)).andExpect(status().isBadRequest());
        entrarCom(ana, codigos.get(0)).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("MFA_RECADASTRO"));
        entrarCom(ana, codigos.get(0)).andExpect(status().isBadRequest());
    }

    // --- Senha, promoção e MFA opcional ---

    @Test
    void redefinirASenhaNaoRemoveOMfa() throws Exception {
        UUID ana = comAutenticador("Ana", MembershipRole.GERENTE);
        String email = IdentityFixtures.email(ana);
        caixa.clear();
        new Browser(mvc).post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}");
        String token = caixa.latestTo(email).orElseThrow().body().replaceAll("(?s).*#token=([A-Za-z0-9_-]+).*", "$1");
        new Browser(mvc).post("/api/v1/auth/password-reset/confirm",
                "{\"token\":\"" + token + "\",\"newPassword\":\"outra frase bem comprida\"}").andExpect(status().isNoContent());

        new Browser(mvc).login(email, "outra frase bem comprida").andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_mfa WHERE identity_id = ?", Integer.class, ana))
                .isEqualTo(1);
    }

    @Test
    void garcomPromovidoAGerenteDuranteASessaoPrecisaCadastrarAntesDaProximaOperacao() throws Exception {
        UUID gil = pessoa("Gil", MembershipRole.GARCOM);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(gil), SENHA).andExpect(jsonPath("$.stage").value("AUTENTICADO"));
        navegador.get("/api/v1/session/units").andExpect(status().isOk());

        admin().update("UPDATE fastpay.membership SET role = 'GERENTE' WHERE identity_id = ?", gil);

        navegador.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_CADASTRO_OBRIGATORIO"));
        byte[] segredo = iniciarCadastro(navegador);
        navegador.post("/api/v1/auth/mfa/setup/confirm", codigo(codigoAgora(segredo)))
                .andExpect(jsonPath("$.session.idleTimeoutSeconds").value(1800));
        navegador.get("/api/v1/session/units").andExpect(status().isOk());
    }

    @Test
    void garcomPodeAtivarMfaPorVontadePropria() throws Exception {
        UUID gil = pessoa("Gil", MembershipRole.GARCOM);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(gil), SENHA).andExpect(jsonPath("$.stage").value("AUTENTICADO"))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(3600));
        navegador.get("/api/v1/auth/mfa").andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.required").value(false));
        String absoluto = JsonPath.read(navegador.get("/api/v1/auth/session").andReturn().getResponse()
                .getContentAsString(), "$.absoluteExpiresAt");

        byte[] segredo = iniciarCadastro(navegador);
        navegador.post("/api/v1/auth/mfa/setup/confirm", codigo(codigoAgora(segredo))).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.absoluteExpiresAt").value(absoluto));

        navegador.get("/api/v1/session/units").andExpect(status().isOk());
        navegador.get("/api/v1/auth/mfa").andExpect(jsonPath("$.enabled").value(true));
        navegador.post("/api/v1/auth/mfa/setup", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_JA_ATIVO"));
        new Browser(mvc).login(IdentityFixtures.email(gil), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
    }

    @Test
    void garcomComMfaAtivadoEmOutraSessaoTambemConfirmaNestaSessao() throws Exception {
        UUID gil = pessoa("Gil", MembershipRole.GARCOM);
        Browser antiga = new Browser(mvc);
        antiga.login(IdentityFixtures.email(gil), SENHA);
        mfa.enroll(gil);

        antiga.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_PENDENTE"));
    }

    @Test
    void cadastroSemIniciarOuSemSessaoEhRecusado() throws Exception {
        new Browser(mvc).post("/api/v1/auth/mfa/setup", "").andExpect(status().isUnauthorized());
        new Browser(mvc).verify("123456").andExpect(status().isUnauthorized());
        UUID ana = pessoa("Ana", MembershipRole.GERENTE);
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(ana), SENHA);

        navegador.post("/api/v1/auth/mfa/setup/confirm", codigo("123456")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CADASTRO_NAO_INICIADO"));
    }

    // --- Apoio ---

    private UUID pessoa(String nome, MembershipRole papel) {
        UUID id = fixtures.identity(nome);
        fixtures.credential(id, SENHA);
        fixtures.membership(id, loja, papel);
        return id;
    }

    private UUID comAutenticador(String nome, MembershipRole papel) {
        UUID id = pessoa(nome, papel);
        mfa.enroll(id);
        return id;
    }

    /** Login com senha e o código dado, num navegador novo. */
    private org.springframework.test.web.servlet.ResultActions entrarCom(UUID identidade, String codigo) throws Exception {
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(identidade), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        return navegador.verify(codigo);
    }

    private byte[] iniciarCadastro(Browser navegador) throws Exception {
        String resposta = navegador.post("/api/v1/auth/mfa/setup", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/fastPay:")))
                .andReturn().getResponse().getContentAsString();
        String segredo = JsonPath.read(resposta, "$.secret");
        assertThat(JsonPath.<String>read(resposta, "$.otpauthUri")).contains("secret=" + segredo.replace(" ", ""));
        return Base32.decode(segredo);
    }

    private String codigoAgora(byte[] segredo) {
        return Totp.code(segredo, Totp.step(relogio.instant()));
    }

    private static String codigo(String codigo) {
        return "{\"code\":\"" + codigo + "\"}";
    }

    private List<String> eventos(UUID identidade) {
        return admin().queryForList("SELECT action FROM fastpay.security_event WHERE identity_id = ? ORDER BY occurred_at, action",
                String.class, identidade);
    }
}
