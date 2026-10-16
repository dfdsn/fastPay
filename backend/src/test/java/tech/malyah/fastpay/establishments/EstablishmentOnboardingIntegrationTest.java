package tech.malyah.fastpay.establishments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.domain.Base32;
import tech.malyah.fastpay.identity.domain.Totp;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.mail.infrastructure.TestMailbox;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Cadastro de estabelecimento e convite do dono pela API (H10, FR-01, FR-02), com PostgreSQL real, RLS, Spring
 * Session, CSRF, fila de jobs e relógio controlado: só o admin fastPay cadastra; a unidade nasce em
 * configuração, sem CNPJ obrigatório e sem nenhum outro efeito; o convite vale 48 h a partir do envio e uma vez
 * só; aceites simultâneos; reenvio invalida o anterior; conta existente precisa entrar para aceitar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EstablishmentOnboardingIntegrationTest.RelogioControlado.class)
class EstablishmentOnboardingIntegrationTest extends PostgresIntegrationTest {

    static final String SENHA = "uma senha longa de teste";
    static final String SENHA_DONO = "frase do dono bem comprida";
    static final Pattern TOKEN = Pattern.compile("/convite#token=([A-Za-z0-9_-]+)");

    @TestConfiguration
    static class RelogioControlado {
        @Bean
        @Primary
        AdjustableClock relogio() {
            // Microssegundos, como o PostgreSQL guarda: prazos comparados sem arredondamento.
            return new AdjustableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
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
    MfaSecretCipher cifra;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    MfaFixtures mfa;
    Browser adminFastPay;
    UUID adminId;

    @BeforeEach
    void entrarComoAdminFastPay() throws Exception {
        mfa = new MfaFixtures(admin(), cifra);
        // Avança um passo de 30 s por teste: o admin de cada teste é outro, mas o relógio é compartilhado.
        relogio.advance(Duration.ofSeconds(30));
        adminId = fixtures.identity("Bia da fastPay", "ADMIN_FASTPAY");
        adminFastPay = entrar(adminId);
    }

    // --- Cadastro ---

    @Test
    void adminFastPayCadastraSemCnpjEmConfiguracaoEConvidaODono() throws Exception {
        String dono = novoEmail();
        String resposta = adminFastPay.post("/api/v1/establishments", cadastro("  Bar do Zé  ", " " + dono.toUpperCase() + " ", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Bar do Zé"))
                .andExpect(jsonPath("$.ownerEmail").value(dono))
                .andExpect(jsonPath("$.document").doesNotExist())
                .andExpect(jsonPath("$.status").value("CONFIGURACAO"))
                .andExpect(jsonPath("$.ownerInvitation.status").value("ENVIO_PENDENTE"))
                .andReturn().getResponse().getContentAsString();
        UUID loja = UUID.fromString(JsonPath.read(resposta, "$.id"));

        // Nenhum efeito além do e-mail do convite: sem mensalidade, cobrança ou ativação (FR-72, H39).
        assertThat(admin().queryForList("SELECT kind FROM fastpay.job WHERE establishment_id = ?", String.class, loja))
                .containsExactly("identity.email_convite");
        assertThat(admin().queryForObject("SELECT created_by FROM fastpay.establishment WHERE id = ?", UUID.class, loja))
                .isEqualTo(adminId);
        assertThat(auditoria(loja)).containsExactly("convite.criado", "estabelecimento.criado");

        Instant envio = relogio.instant();
        TestMailbox.Message convite = entregues().latestTo(dono).orElseThrow();
        assertThat(convite.subject()).isEqualTo("[Desenvolvimento] Convite para Bar do Zé no fastPay");
        assertThat(convite.body()).contains("Bia da fastPay convidou você para participar de Bar do Zé no fastPay "
                + "como administrador do estabelecimento");
        String token = token(convite);
        // Só o hash fica no banco; o prazo de 48 h conta do envio.
        assertThat(admin().queryForList("SELECT token_hash FROM fastpay.invitation WHERE establishment_id = ?",
                String.class, loja)).singleElement().isNotEqualTo(token);
        assertThat(admin().queryForObject("SELECT expires_at FROM fastpay.invitation WHERE establishment_id = ?",
                java.sql.Timestamp.class, loja).toInstant())
                .isEqualTo(envio.plus(Duration.ofHours(48)));

        adminFastPay.get("/api/v1/establishments").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].ownerInvitation.status".formatted(loja)).value("ENVIADO"));

        // Dono sem conta: vê o convite, cria a senha no aceite e entra com MFA obrigatório.
        Browser donoNavegador = new Browser(mvc);
        donoNavegador.post("/api/v1/invitations/preview", tokenJson(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.establishmentName").value("Bar do Zé"))
                .andExpect(jsonPath("$.role").value("ADMIN_ESTABELECIMENTO"))
                .andExpect(jsonPath("$.roleLabel").value("administrador do estabelecimento"))
                .andExpect(jsonPath("$.email").value(dono))
                .andExpect(jsonPath("$.accountExists").value(false))
                .andExpect(jsonPath("$.signedIn").value(false));
        donoNavegador.post("/api/v1/invitations/accept", aceite(token, "Zé", "curta"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DADOS_RECUSADOS"));
        donoNavegador.post("/api/v1/invitations/accept", aceite(token, "Zé", SENHA_DONO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.establishmentId").value(loja.toString()))
                .andExpect(jsonPath("$.signedIn").value(false));

        UUID donoId = admin().queryForObject("SELECT id FROM fastpay.user_identity WHERE email = ?", UUID.class, dono);
        assertThat(admin().queryForObject("SELECT platform_role FROM fastpay.user_identity WHERE id = ?", String.class,
                donoId)).isNull();
        assertThat(auditoria(loja)).contains("convite.aceito");
        adminFastPay.get("/api/v1/establishments").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].ownerInvitation.status".formatted(loja)).value("ACEITO"))
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(loja)).value("CONFIGURACAO"));

        // Primeiro acesso: o papel de administrador do estabelecimento exige cadastrar o autenticador.
        donoNavegador.login(dono, SENHA_DONO).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"));
        String setup = donoNavegador.post("/api/v1/auth/mfa/setup", "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        byte[] segredo = Base32.decode(JsonPath.read(setup, "$.secret"));
        donoNavegador.post("/api/v1/auth/mfa/setup/confirm",
                "{\"code\":\"" + Totp.code(segredo, Totp.step(relogio.instant())) + "\"}").andExpect(status().isOk());
        donoNavegador.get("/api/v1/session/units").andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].establishmentId").value(loja.toString()))
                .andExpect(jsonPath("$.units[0].role").value("ADMIN_ESTABELECIMENTO"))
                .andExpect(jsonPath("$.platformAdmin").value(false));
        adminFastPay.get("/api/v1/session/units").andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.units.length()").value(0));
        // Dono não é admin fastPay.
        donoNavegador.get("/api/v1/establishments").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACESSO_RESTRITO_PLATAFORMA"));

        // Usar o mesmo convite de novo não vale, nem reenviar depois do aceite.
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(token, "Outra pessoa", SENHA_DONO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVITE_INVALIDO"));
        adminFastPay.post("/api/v1/establishments/" + loja + "/owner-invitation", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DONO_JA_ACEITOU"));
    }

    @Test
    void cnpjEOpcionalMasQuandoInformadoPrecisaSerValido() throws Exception {
        adminFastPay.post("/api/v1/establishments", cadastro("Bar", novoEmail(), "11.222.333/0001-80"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DADOS_RECUSADOS"))
                .andExpect(jsonPath("$.field").value("document"));
        adminFastPay.post("/api/v1/establishments", cadastro("Bar", "sem-arroba", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ownerEmail"));
        adminFastPay.post("/api/v1/establishments", cadastro(" ", novoEmail(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));

        adminFastPay.post("/api/v1/establishments", cadastro("Bar Numérico", novoEmail(), "11.222.333/0001-81"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.document").value("11222333000181"));
        adminFastPay.post("/api/v1/establishments", cadastro("Bar Alfanumérico", novoEmail(), "12.abc.345/01de-35"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.document").value("12ABC34501DE35"));
    }

    // --- Quem pode cadastrar ---

    @Test
    void lojaGarcomESessaoAusenteNaoCadastramNemListam() throws Exception {
        UUID unidade = fixtures.establishment("Bar existente");
        UUID dona = fixtures.identity("Dona de loja");
        fixtures.membership(dona, unidade, MembershipRole.ADMIN_ESTABELECIMENTO);
        UUID garcom = fixtures.identity("Garçom");
        fixtures.membership(garcom, unidade, MembershipRole.GARCOM);
        fixtures.credential(garcom, SENHA);
        Browser donaNavegador = entrar(dona);
        Browser garcomNavegador = new Browser(mvc);
        garcomNavegador.login(IdentityFixtures.email(garcom), SENHA).andExpect(jsonPath("$.stage").value("AUTENTICADO"));
        long antes = admin().queryForObject("SELECT count(*) FROM fastpay.establishment", Long.class);

        for (Browser outro : List.of(donaNavegador, garcomNavegador)) {
            outro.post("/api/v1/establishments", cadastro("Bar pirata", novoEmail(), null))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACESSO_RESTRITO_PLATAFORMA"));
            outro.get("/api/v1/establishments").andExpect(status().isForbidden());
            outro.post("/api/v1/establishments/" + unidade + "/owner-invitation", "").andExpect(status().isForbidden());
        }
        new Browser(mvc).post("/api/v1/establishments", cadastro("Bar pirata", novoEmail(), null))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"));
        // Admin fastPay ainda na etapa do MFA também não.
        UUID outroAdmin = fixtures.identity("Admin sem código", "ADMIN_FASTPAY");
        fixtures.credential(outroAdmin, SENHA);
        mfa.enroll(outroAdmin);
        Browser pendente = new Browser(mvc);
        pendente.login(IdentityFixtures.email(outroAdmin), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        pendente.post("/api/v1/establishments", cadastro("Bar pirata", novoEmail(), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_PENDENTE"));

        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.establishment", Long.class)).isEqualTo(antes);
    }

    @Test
    void papelGlobalNaoAbreDadosOperacionaisDasLojas() throws Exception {
        UUID unidade = fixtures.establishment("Bar com equipe");
        fixtures.membership(fixtures.identity("Gil"), unidade, MembershipRole.GARCOM);
        admin().update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.acao')",
                UUID.randomUUID(), unidade);

        long[] visiveis = tenants.asIdentity(adminId, () -> new long[] {
                jdbc.queryForObject("SELECT count(*) FROM membership", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM job", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM establishment WHERE id = ?", Long.class, unidade)});
        assertThat(visiveis).containsExactly(0, 0, 0, 1);
    }

    // --- Convite ---

    @Test
    void conviteVenceEm48HorasDoEnvio() throws Exception {
        String dono = novoEmail();
        cadastrar("Bar que demora", dono);
        String token = token(entregues().latestTo(dono).orElseThrow());

        relogio.advance(Duration.ofHours(48).minusSeconds(1));
        new Browser(mvc).post("/api/v1/invitations/preview", tokenJson(token)).andExpect(status().isOk());
        relogio.advance(Duration.ofSeconds(1));
        new Browser(mvc).post("/api/v1/invitations/preview", tokenJson(token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONVITE_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("Este convite não vale mais. Peça um novo a quem convidou você."));
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(token, "Zé", SENHA_DONO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVITE_INVALIDO"));
        // A sessão do admin venceu com o relógio; entra de novo.
        entrar(adminId).get("/api/v1/establishments").andExpect(jsonPath(
                "$[?(@.ownerEmail == '%s')].ownerInvitation.status".formatted(dono)).value("VENCIDO"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_identity WHERE email = ?", Long.class, dono))
                .isZero();
    }

    @Test
    void reenvioInvalidaOConviteAnterior() throws Exception {
        String dono = novoEmail();
        UUID loja = cadastrar("Bar do reenvio", dono);
        String primeiro = token(entregues().latestTo(dono).orElseThrow());

        adminFastPay.post("/api/v1/establishments/" + loja + "/owner-invitation", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerInvitation.status").value("ENVIO_PENDENTE"));
        String segundo = token(entregues().latestTo(dono).orElseThrow());

        assertThat(segundo).isNotEqualTo(primeiro);
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(primeiro, "Zé", SENHA_DONO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVITE_INVALIDO"));
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(segundo, "Zé", SENHA_DONO))
                .andExpect(status().isCreated());
        assertThat(auditoria(loja)).contains("convite.reenviado", "convite.aceito");
        assertThat(admin().queryForList("""
                SELECT (invalidated_at IS NOT NULL)::text || '/' || (accepted_at IS NOT NULL)::text
                  FROM fastpay.invitation WHERE establishment_id = ? ORDER BY created_at
                """, String.class, loja)).containsExactly("true/false", "false/true");
        adminFastPay.post("/api/v1/establishments/" + UUID.randomUUID() + "/owner-invitation", "")
                .andExpect(status().isNotFound());
    }

    @Test
    void aceitesSimultaneosCriamUmaContaEUmVinculo() throws Exception {
        String dono = novoEmail();
        UUID loja = cadastrar("Bar concorrido", dono);
        String token = token(entregues().latestTo(dono).orElseThrow());

        int abas = 4;
        CountDownLatch largada = new CountDownLatch(1);
        List<Callable<Integer>> tentativas = new ArrayList<>();
        for (int i = 0; i < abas; i++) {
            Browser aba = new Browser(mvc);
            aba.get("/api/v1/platform/info");
            tentativas.add(() -> {
                largada.await();
                return aba.post("/api/v1/invitations/accept", aceite(token, "Zé", SENHA_DONO))
                        .andReturn().getResponse().getStatus();
            });
        }
        ExecutorService executor = Executors.newFixedThreadPool(abas);
        try {
            List<Future<Integer>> resultados = new ArrayList<>();
            for (Callable<Integer> tentativa : tentativas) {
                resultados.add(executor.submit(tentativa));
            }
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> resultado : resultados) {
                status.add(resultado.get());
            }
            assertThat(status).containsOnlyOnce(201).doesNotContain(500);
        } finally {
            executor.shutdownNow();
        }
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_identity WHERE email = ?", Long.class, dono))
                .isOne();
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.membership WHERE establishment_id = ?",
                Long.class, loja)).isOne();
    }

    @Test
    void contaExistenteEntraParaAceitarComOEmailDoConvite() throws Exception {
        // Gil já é garçom em outra loja e tem conta com o e-mail convidado.
        UUID outraLoja = fixtures.establishment("Outra loja");
        UUID gil = fixtures.identity("Gil");
        fixtures.membership(gil, outraLoja, MembershipRole.GARCOM);
        fixtures.credential(gil, SENHA);
        String emailGil = IdentityFixtures.email(gil);
        UUID loja = cadastrar("Bar do Gil", emailGil);
        String token = token(entregues().latestTo(emailGil).orElseThrow());

        new Browser(mvc).post("/api/v1/invitations/preview", tokenJson(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountExists").value(true));
        // Sem sessão não cria outra conta nem troca a senha.
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(token, "Impostor", SENHA_DONO))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTRE_PARA_ACEITAR"));
        // Outra conta autenticada não aceita por ele.
        UUID ana = fixtures.identity("Ana");
        fixtures.membership(ana, outraLoja, MembershipRole.GARCOM);
        fixtures.credential(ana, SENHA);
        Browser anaNavegador = new Browser(mvc);
        anaNavegador.login(IdentityFixtures.email(ana), SENHA);
        anaNavegador.post("/api/v1/invitations/accept", tokenJson(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONVITE_DE_OUTRA_CONTA"));

        Browser gilNavegador = new Browser(mvc);
        gilNavegador.login(emailGil, SENHA).andExpect(jsonPath("$.stage").value("AUTENTICADO"));
        gilNavegador.post("/api/v1/invitations/preview", tokenJson(token)).andExpect(jsonPath("$.signedIn").value(true));
        gilNavegador.post("/api/v1/invitations/accept", tokenJson(token)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.signedIn").value(true));

        assertThat(admin().queryForList("SELECT role FROM fastpay.membership WHERE identity_id = ? ORDER BY role",
                String.class, gil)).containsExactly("ADMIN_ESTABELECIMENTO", "GARCOM");
        // Virou administrador de loja: a sessão sem segundo fator volta para o cadastro do autenticador.
        gilNavegador.get("/api/v1/session/units").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_CADASTRO_OBRIGATORIO"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.membership WHERE establishment_id = ?",
                Long.class, loja)).isOne();
    }

    @Test
    void tokensInvalidosRepetidosSaoLimitadosPorOrigem() throws Exception {
        Browser atacante = new Browser(mvc);
        for (int i = 0; i < 10; i++) {
            atacante.post("/api/v1/invitations/preview", tokenJson("token-inventado-" + i + "-abcdefgh"))
                    .andExpect(status().isBadRequest());
        }
        atacante.post("/api/v1/invitations/preview", tokenJson("token-inventado-x-abcdefgh"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("MUITAS_TENTATIVAS"));
    }

    // --- Apoio ---

    private Browser entrar(UUID identidade) throws Exception {
        fixtures.credential(identidade, SENHA);
        if (!mfa.enrolled(identidade)) {
            mfa.enroll(identidade);
        }
        Browser navegador = new Browser(mvc);
        navegador.login(IdentityFixtures.email(identidade), SENHA).andExpect(jsonPath("$.stage").value("MFA_PENDENTE"));
        navegador.verify(mfa.nextCode(identidade, relogio.instant())).andExpect(status().isOk());
        return navegador;
    }

    private UUID cadastrar(String nome, String dono) throws Exception {
        String resposta = adminFastPay.post("/api/v1/establishments", cadastro(nome, dono, null))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(resposta, "$.id"));
    }

    private List<String> auditoria(UUID loja) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE establishment_id = ? ORDER BY action",
                String.class, loja);
    }

    private static String novoEmail() {
        return "dono-" + UUID.randomUUID() + "@teste.invalid";
    }

    private static String cadastro(String nome, String email, String documento) {
        return "{\"name\":%s,\"ownerEmail\":%s,\"document\":%s}".formatted(json(nome), json(email), json(documento));
    }

    private static String aceite(String token, String nome, String senha) {
        return "{\"token\":%s,\"displayName\":%s,\"password\":%s}".formatted(json(token), json(nome), json(senha));
    }

    private static String tokenJson(String token) {
        return "{\"token\":" + json(token) + "}";
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String token(TestMailbox.Message mensagem) {
        Matcher matcher = TOKEN.matcher(mensagem.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Executa os e-mails na fila (o worker periódico fica desligado nos testes) e devolve a caixa de teste. */
    private TestMailbox entregues() {
        while (worker.runOnce() > 0) {
            // até não haver mais jobs vencidos
        }
        return caixa;
    }
}
