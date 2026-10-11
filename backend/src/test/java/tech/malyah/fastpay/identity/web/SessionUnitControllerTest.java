package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.mock.web.MockHttpSession;
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
 * forjados, vínculo revogado com a unidade já escolhida e identidade com papéis distintos. A identidade
 * é posta na sessão diretamente porque o login chega em H08.
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

    @BeforeEach
    void duasLojas() {
        lojaA = fixtures.establishment("Bar da Praia");
        lojaB = fixtures.establishment("Adega Centro");
        ana = fixtures.identity("Ana");
        bruno = fixtures.identity("Bruno");
        vinculoAnaA = fixtures.membership(ana, lojaA, MembershipRole.GERENTE);
        fixtures.membership(ana, lojaB, MembershipRole.GARCOM);
        fixtures.membership(bruno, lojaA, MembershipRole.PRODUCAO);
        admin().update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.a'), (?, ?, 'teste.b')",
                UUID.randomUUID(), lojaA, UUID.randomUUID(), lojaB);
    }

    @Test
    void semIdentidadeNaSessaoResponde401EmPortugues() throws Exception {
        mvc.perform(get("/api/v1/session/units"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SESSAO_AUSENTE"))
                .andExpect(jsonPath("$.detail").value("Sua sessão não está ativa. Entre novamente."));
        mvc.perform(put("/api/v1/session/unit").contentType(MediaType.APPLICATION_JSON).content(corpo(lojaA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listaUnidadesDaIdentidadeComPapelDeCada() throws Exception {
        mvc.perform(get("/api/v1/session/units").session(sessao(ana)))
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
        MockHttpSession sessao = sessao(ana);
        String idAntes = sessao.getId();

        selecionar(sessao, lojaA).andExpect(status().isOk())
                .andExpect(jsonPath("$.establishmentId").value(lojaA.toString()))
                .andExpect(jsonPath("$.name").value("Bar da Praia"))
                .andExpect(jsonPath("$.role").value("GERENTE"));

        assertThat(sessao.getId()).isNotEqualTo(idAntes);
        assertThat(sessao.getAttribute(SessionContext.UNIT_ATTRIBUTE)).isEqualTo(lojaA);
        mvc.perform(get("/api/v1/session/unit").session(sessao))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GERENTE"));
        mvc.perform(get("/api/v1/session/units").session(sessao))
                .andExpect(jsonPath("$.currentEstablishmentId").value(lojaA.toString()));
    }

    @Test
    void trocarDeUnidadeLimitaConsultasAoNovoVinculo() throws Exception {
        MockHttpSession sessao = sessao(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());
        mvc.perform(get("/api/v1/test/unidade/eventos").session(sessao))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.a")));

        selecionar(sessao, lojaB).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GARCOM"));

        mvc.perform(get("/api/v1/test/unidade/eventos").session(sessao))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.b")));
        mvc.perform(get("/api/v1/test/unidade/estabelecimentos/" + lojaA).session(sessao))
                .andExpect(status().isNotFound());
    }

    @Test
    void unidadeForjadaOuInexistenteRecebeAMesmaRecusa() throws Exception {
        MockHttpSession sessao = sessao(bruno);
        String alheia = selecionar(sessao, lojaB).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"))
                .andReturn().getResponse().getContentAsString();
        String inexistente = selecionar(sessao, UUID.randomUUID()).andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(inexistente).isEqualTo(alheia);
        assertThat(alheia).doesNotContain(lojaB.toString()).doesNotContain("Adega");
        assertThat(sessao.getAttribute(SessionContext.UNIT_ATTRIBUTE)).isNull();
    }

    @Test
    void idsDeOutraLojaEmCaminhoOuHeaderNaoMudamAUnidade() throws Exception {
        MockHttpSession sessao = sessao(bruno);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        mvc.perform(get("/api/v1/test/unidade/estabelecimentos/" + lojaB).session(sessao))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/test/unidade/eventos").session(sessao).header("X-Establishment-Id", lojaB.toString()))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("teste.a")));
        // Recusa de outra loja não derruba a escolha válida.
        selecionar(sessao, lojaB).andExpect(status().isForbidden());
        assertThat(sessao.getAttribute(SessionContext.UNIT_ATTRIBUTE)).isEqualTo(lojaA);
    }

    @Test
    void vinculoRevogadoComUnidadeEscolhidaPerdeAcessoNaProximaOperacao() throws Exception {
        MockHttpSession sessao = sessao(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        memberships.deactivate(lojaA, vinculoAnaA, bruno, null);

        mvc.perform(get("/api/v1/test/unidade/eventos").session(sessao))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
        assertThat(sessao.getAttribute(SessionContext.UNIT_ATTRIBUTE)).as("escolha esquecida").isNull();
        mvc.perform(get("/api/v1/session/unit").session(sessao))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
        mvc.perform(get("/api/v1/session/units").session(sessao))
                .andExpect(jsonPath("$.units.length()").value(1))
                .andExpect(jsonPath("$.units[0].name").value("Adega Centro"));
        selecionar(sessao, lojaB).andExpect(status().isOk());
    }

    @Test
    void listaDeUnidadesDescartaEscolhaJaRevogada() throws Exception {
        MockHttpSession sessao = sessao(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());
        memberships.deactivate(lojaA, vinculoAnaA, bruno, null);

        mvc.perform(get("/api/v1/session/units").session(sessao))
                .andExpect(jsonPath("$.currentEstablishmentId").doesNotExist());
        assertThat(sessao.getAttribute(SessionContext.UNIT_ATTRIBUTE)).isNull();
    }

    @Test
    void adminFastPaySemVinculoNaoOperaLoja() throws Exception {
        UUID adminFastPay = fixtures.identity("Admin plataforma", "ADMIN_FASTPAY");
        MockHttpSession sessao = sessao(adminFastPay);

        mvc.perform(get("/api/v1/session/units").session(sessao))
                .andExpect(status().isOk()).andExpect(jsonPath("$.units.length()").value(0));
        selecionar(sessao, lojaA).andExpect(status().isForbidden());
    }

    @Test
    void sairDaUnidadeExigeNovaEscolha() throws Exception {
        MockHttpSession sessao = sessao(ana);
        selecionar(sessao, lojaA).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/session/unit").session(sessao)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/test/unidade/eventos").session(sessao)).andExpect(status().isConflict());
    }

    @Test
    void pedidoSemUnidadeEhInvalido() throws Exception {
        mvc.perform(put("/api/v1/session/unit").session(sessao(ana)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUISICAO_INVALIDA"))
                .andExpect(jsonPath("$.detail").value("Escolha uma unidade."));
    }

    @Test
    void identidadeInexistenteNaSessaoEhTratadaComoSemSessao() throws Exception {
        mvc.perform(get("/api/v1/session/units").session(sessao(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions selecionar(MockHttpSession sessao, UUID unidade) throws Exception {
        return mvc.perform(put("/api/v1/session/unit").session(sessao)
                .contentType(MediaType.APPLICATION_JSON).content(corpo(unidade)));
    }

    private static String corpo(UUID unidade) {
        return "{\"establishmentId\":\"" + unidade + "\"}";
    }

    private static MockHttpSession sessao(UUID identidade) {
        MockHttpSession sessao = new MockHttpSession();
        sessao.setAttribute(SessionContext.IDENTITY_ATTRIBUTE, identidade);
        return sessao;
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
