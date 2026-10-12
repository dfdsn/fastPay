package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Seleção e troca de unidade pela API (H07, FR-03, CA-03), com PostgreSQL real: duas lojas, IDs
 * forjados, vínculo revogado com a unidade já escolhida e identidade com papéis distintos. Cada pessoa
 * entra pelo login real (H08), com sessão no Spring Session JDBC e CSRF.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionUnitControllerTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Memberships memberships;

    IdentityFixtures fixtures = new IdentityFixtures(admin());

    UUID lojaA;
    UUID lojaB;
    UUID ana;
    UUID bruno;
    UUID vinculoAnaA;

    static final String SENHA = "uma senha longa de teste";

    @BeforeEach
    void duasLojas() {
        lojaA = fixtures.establishment("Bar da Praia");
        lojaB = fixtures.establishment("Adega Centro");
        ana = fixtures.identity("Ana");
        bruno = fixtures.identity("Bruno");
        fixtures.credential(ana, SENHA);
        fixtures.credential(bruno, SENHA);
        vinculoAnaA = fixtures.membership(ana, lojaA, MembershipRole.GERENTE);
        fixtures.membership(ana, lojaB, MembershipRole.GARCOM);
        fixtures.membership(bruno, lojaA, MembershipRole.PRODUCAO);
        admin().update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.a'), (?, ?, 'teste.b')",
                UUID.randomUUID(), lojaA, UUID.randomUUID(), lojaB);
    }

    @Test
    void semIdentidadeNaSessaoResponde401EmPortugues() throws Exception {
        Browser anonimo = new Browser(mvc);
        anonimo.get("/api/v1/session/units")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"))
                .andExpect(jsonPath("$.detail").value("Sua sessão não está ativa. Entre novamente."));
        selecionar(anonimo, lojaA).andExpect(status().isUnauthorized());
    }

    @Test
    void listaUnidadesDaIdentidadeComPapelDeCada() throws Exception {
        entrar(ana).get("/api/v1/session/units")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ana"))
                .andExpect(jsonPath("$.units.length()").value(2))
                .andExpect(jsonPath("$.units[0].name").value("Adega Centro"))
                .andExpect(jsonPath("$.units[0].role").value("GARCOM"))
                .andExpect(jsonPath("$.units[1].name").value("Bar da Praia"))
                .andExpect(jsonPath("$.units[1].role").value("GERENTE"))
                .andExpect(jsonPath("$.currentEstablishmentId").doesNotExist());
    }

    @Test
    void escolherUnidadeGuardaNaSessaoTrocaOIdDaSessaoEMostraOPapel() throws Exception {
        Browser sessao = entrar(ana);
        String idAntes = sessao.cookie("FASTPAY_SESSION").orElseThrow();

        selecionar(sessao, lojaA).andExpect(status().isOk())
                .andExpect(jsonPath("$.establishmentId").value(lojaA.toString()))
                .andExpect(jsonPath("$.name").value("Bar da Praia"))
                .andExpect(jsonPath("$.role").value("GERENTE"));

        assertThat(sessao.cookie("FASTPAY_SESSION")).hasValueSatisfying(id -> assertThat(id).isNotEqualTo(idAntes));
        sessao.get("/api/v1/session/unit")
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GERENTE"));
        sessao.get("/api/v1/session/units")
                .andExpect(jsonPath("$.currentEstablishmentId").value(lojaA.toString()));
    }

    @Test
    void trocarDeUnidadeLimitaConsultasAoNovoVinculo() throws Exception {
        Browser sessao = entrar(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());
        sessao.get("/api/v1/test/unidade/eventos")
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.a")));

        selecionar(sessao, lojaB).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GARCOM"));

        sessao.get("/api/v1/test/unidade/eventos")
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.b")));
        sessao.get("/api/v1/test/unidade/estabelecimentos/" + lojaA)
                .andExpect(status().isNotFound());
    }

    @Test
    void unidadeForjadaOuInexistenteRecebeAMesmaRecusa() throws Exception {
        Browser sessao = entrar(bruno);
        String alheia = selecionar(sessao, lojaB).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"))
                .andReturn().getResponse().getContentAsString();
        String inexistente = selecionar(sessao, UUID.randomUUID()).andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(inexistente).isEqualTo(alheia);
        assertThat(alheia).doesNotContain(lojaB.toString()).doesNotContain("Adega");
        sessao.get("/api/v1/session/unit").andExpect(status().isConflict());
    }

    @Test
    void idsDeOutraLojaEmCaminhoOuHeaderNaoMudamAUnidade() throws Exception {
        Browser sessao = entrar(bruno);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        sessao.get("/api/v1/test/unidade/estabelecimentos/" + lojaB)
                .andExpect(status().isNotFound());
        sessao.send(get("/api/v1/test/unidade/eventos").header("X-Establishment-Id", lojaB.toString()))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.a")));
        // Recusa de outra loja não derruba a escolha válida.
        selecionar(sessao, lojaB).andExpect(status().isForbidden());
        sessao.get("/api/v1/session/unit").andExpect(jsonPath("$.establishmentId").value(lojaA.toString()));
    }

    @Test
    void vinculoRevogadoComUnidadeEscolhidaPerdeAcessoNaProximaOperacao() throws Exception {
        Browser sessao = entrar(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        memberships.deactivate(lojaA, vinculoAnaA, bruno, null);

        sessao.get("/api/v1/test/unidade/eventos")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
        // Escolha esquecida: volta à seleção.
        sessao.get("/api/v1/session/unit")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
        sessao.get("/api/v1/session/units")
                .andExpect(jsonPath("$.units.length()").value(1))
                .andExpect(jsonPath("$.units[0].name").value("Adega Centro"));
        selecionar(sessao, lojaB).andExpect(status().isOk());
    }

    @Test
    void listaDeUnidadesDescartaEscolhaJaRevogada() throws Exception {
        Browser sessao = entrar(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());
        memberships.deactivate(lojaA, vinculoAnaA, bruno, null);

        sessao.get("/api/v1/session/units")
                .andExpect(jsonPath("$.currentEstablishmentId").doesNotExist());
        sessao.get("/api/v1/session/unit").andExpect(status().isConflict());
    }

    @Test
    void adminFastPaySemVinculoNaoOperaLoja() throws Exception {
        UUID adminFastPay = fixtures.identity("Admin plataforma", "ADMIN_FASTPAY");
        fixtures.credential(adminFastPay, SENHA);
        Browser sessao = entrar(adminFastPay);

        sessao.get("/api/v1/session/units")
                .andExpect(status().isOk()).andExpect(jsonPath("$.units.length()").value(0));
        selecionar(sessao, lojaA).andExpect(status().isForbidden());
    }

    @Test
    void sairDaUnidadeExigeNovaEscolha() throws Exception {
        Browser sessao = entrar(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        sessao.delete("/api/v1/session/unit").andExpect(status().isNoContent());

        sessao.get("/api/v1/test/unidade/eventos").andExpect(status().isConflict());
    }

    @Test
    void pedidoSemUnidadeEhInvalido() throws Exception {
        entrar(ana).put("/api/v1/session/unit", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUISICAO_INVALIDA"))
                .andExpect(jsonPath("$.detail").value("Escolha uma unidade."));
    }

    @Test
    void trocarDeUnidadeSemTokenCsrfEhRecusado() throws Exception {
        Browser sessao = entrar(ana);

        sessao.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/session/unit")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(lojaA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAGINA_DESATUALIZADA"));
        sessao.get("/api/v1/session/unit").andExpect(status().isConflict());
    }

    private Browser entrar(UUID identidade) throws Exception {
        Browser browser = new Browser(mvc);
        browser.login(IdentityFixtures.email(identidade), SENHA).andExpect(status().isOk());
        return browser;
    }

    private static ResultActions selecionar(Browser sessao, UUID unidade) throws Exception {
        return sessao.put("/api/v1/session/unit", corpo(unidade));
    }

    private static String corpo(UUID unidade) {
        return "{\"establishmentId\":\"" + unidade + "\"}";
    }

    /**
     * Operações de loja de exemplo, só para teste: mostram como um controller usa a unidade da sessão
     * revalidada, sem aceitar unidade vinda de caminho, parâmetro ou header.
     */
    @TestConfiguration
    static class OperacoesDeExemplo {

        @RestController
        static class UnidadeDeExemploController {

            private final Memberships memberships;
            private final SessionContext session;
            private final JdbcTemplate jdbc;

            UnidadeDeExemploController(Memberships memberships, SessionContext session, JdbcTemplate jdbc) {
                this.memberships = memberships;
                this.session = session;
                this.jdbc = jdbc;
            }

            @GetMapping("/api/v1/test/unidade/eventos")
            List<String> eventos(HttpServletRequest request) {
                return memberships.inUnit(session.requireIdentity(request), session.requireSelectedUnit(request),
                        m -> jdbc.queryForList("SELECT action FROM audit_event ORDER BY action", String.class));
            }

            @GetMapping("/api/v1/test/unidade/estabelecimentos/{id}")
            org.springframework.http.ResponseEntity<String> estabelecimento(@PathVariable UUID id, HttpServletRequest request) {
                List<String> nomes = memberships.inUnit(session.requireIdentity(request), session.requireSelectedUnit(request),
                        m -> jdbc.queryForList("SELECT name FROM establishment WHERE id = ?", String.class, id));
                return nomes.isEmpty() ? org.springframework.http.ResponseEntity.notFound().build()
                        : org.springframework.http.ResponseEntity.ok(nomes.getFirst());
            }
        }
    }
}
