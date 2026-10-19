package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.PermissionDeniedException;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.mail.infrastructure.TestMailbox;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Equipe, permissões e delegação pela API (H11, FR-02–06, CA-04; PRD §4.2), com PostgreSQL real, RLS, Spring
 * Session e CSRF: o dono convida papéis, troca papel e desativa; o gerente só delega ações delegáveis a garçons;
 * mudanças valem na sessão ativa; ninguém altera o próprio vínculo; sempre resta um administrador; outra loja não é
 * alcançada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class TeamIntegrationTest extends PostgresIntegrationTest {

    static final Pattern TOKEN = Pattern.compile("/convite#token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    TestMailbox caixa;

    @Autowired
    Memberships memberships;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID vinculoGerente;
    UUID vinculoGarcom;
    UUID vinculoProducao;
    UUID vinculoDono;

    @BeforeEach
    void montarLoja() {
        // Um passo de 30 s por teste: cada pessoa entra com um código novo do autenticador.
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Equipe");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gil Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        vinculoDono = fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        vinculoProducao = fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
    }

    @Test
    void donoConvidaPapelEPessoaEntraNaEquipe() throws Exception {
        Browser donaRita = login.inUnit(dono, loja);
        String email = "nova-" + UUID.randomUUID() + "@teste.invalid";

        donaRita.post("/api/v1/team/invitations", "{\"email\":\" " + email.toUpperCase() + " \",\"role\":\"GERENTE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingInvitations[0].email").value(email))
                .andExpect(jsonPath("$.pendingInvitations[0].role").value("GERENTE"))
                .andExpect(jsonPath("$.pendingInvitations[0].status").value("ENVIO_PENDENTE"))
                .andExpect(jsonPath("$.members.length()").value(4));
        String primeiro = token(email);

        // Reenviar invalida o anterior; quem já participa não é convidado de novo.
        String convite = admin().queryForObject("""
                SELECT id::text FROM fastpay.invitation WHERE establishment_id = ? AND email = ? AND invalidated_at IS NULL
                """, String.class, loja, email);
        donaRita.post("/api/v1/team/invitations/" + convite + "/resend", "").andExpect(status().isOk());
        String segundo = token(email);
        assertThat(segundo).isNotEqualTo(primeiro);
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(primeiro, "Nova Gerente"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVITE_INVALIDO"));
        new Browser(mvc).post("/api/v1/invitations/accept", aceite(segundo, "Nova Gerente"))
                .andExpect(status().isCreated());

        donaRita.get("/api/v1/team").andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingInvitations.length()").value(0))
                .andExpect(jsonPath("$.members[?(@.email == '%s')].role".formatted(email)).value("GERENTE"))
                .andExpect(jsonPath("$.delegable.length()").value(10));
        donaRita.post("/api/v1/team/invitations", "{\"email\":\"" + email + "\",\"role\":\"GARCOM\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JA_PARTICIPA"));
        assertThat(auditoria()).contains("convite.criado", "convite.reenviado", "convite.aceito");

        // Convite cancelado não vale mais.
        String outro = "cancelado-" + UUID.randomUUID() + "@teste.invalid";
        donaRita.post("/api/v1/team/invitations", "{\"email\":\"" + outro + "\",\"role\":\"PRODUCAO\"}")
                .andExpect(status().isOk());
        String cancelado = admin().queryForObject(
                "SELECT id::text FROM fastpay.invitation WHERE establishment_id = ? AND email = ?", String.class, loja, outro);
        donaRita.delete("/api/v1/team/invitations/" + cancelado).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingInvitations.length()").value(0));
        donaRita.delete("/api/v1/team/invitations/" + cancelado).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVITE_NAO_ENCONTRADO"));

        donaRita.post("/api/v1/team/invitations", "{\"email\":\"sem-arroba\",\"role\":\"GARCOM\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMAIL_INVALIDO"));
        donaRita.post("/api/v1/team/invitations", "{\"email\":\"x@teste.invalid\",\"role\":\"DONO\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUISICAO_INVALIDA"));
    }

    @Test
    void gerenteVeEDelegaMasNaoGereEquipe() throws Exception {
        Browser gil = login.inUnit(gerente, loja);

        gil.get("/api/v1/team").andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(4))
                .andExpect(jsonPath("$.currentMembershipId").value(vinculoGerente.toString()));
        gil.post("/api/v1/team/invitations", "{\"email\":\"x@teste.invalid\",\"role\":\"GARCOM\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        gil.put("/api/v1/team/members/" + vinculoGarcom + "/role", "{\"role\":\"GERENTE\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        gil.post("/api/v1/team/members/" + vinculoGarcom + "/deactivate", "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        gil.put(permissoes(vinculoGarcom), "{\"permissions\":[\"CANCELAR_ITEM\",\"DESFAZER_ENTREGA\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.membershipId == '%s')].delegated[*]".formatted(vinculoGarcom))
                        .value(org.hamcrest.Matchers.containsInAnyOrder("CANCELAR_ITEM", "DESFAZER_ENTREGA")));

        // Devolução, débito e ações do administrador não são delegáveis; só garçom recebe; nunca o próprio vínculo.
        for (String exclusiva : List.of("AUTORIZAR_DEVOLUCAO", "ENCERRAR_COM_DEBITO", "INVALIDAR_RECEBIMENTO_EXTERNO",
                "GERIR_EQUIPE", "AUTORIZAR_SUPORTE")) {
            gil.put(permissoes(vinculoGarcom), "{\"permissions\":[\"" + exclusiva + "\"]}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NAO_DELEGAVEL"));
        }
        gil.put(permissoes(vinculoProducao), "{\"permissions\":[\"CANCELAR_ITEM\"]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PAPEL_NAO_RECEBE"));
        gil.put(permissoes(vinculoGerente), "{\"permissions\":[]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROPRIO_VINCULO"));
        gil.put(permissoes(vinculoGarcom), "{\"permissions\":[\"VOAR\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUISICAO_INVALIDA"));
        // Nada mudou com as recusas.
        assertThat(delegadas(vinculoGarcom)).containsExactlyInAnyOrder("CANCELAR_ITEM", "DESFAZER_ENTREGA");

        // Garçom e produção não veem a equipe nem delegam, mesmo chamando a API direto.
        Browser gabi = login.inUnit(garcom, loja);
        gabi.get("/api/v1/team").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        gabi.put(permissoes(vinculoGarcom), "{\"permissions\":[\"CONCEDER_DESCONTO\"]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        login.inUnit(producao, loja).get("/api/v1/team").andExpect(status().isForbidden());
    }

    @Test
    void permissaoDelegadaERevogadaValeNaSessaoAtiva() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        gabi.get("/api/v1/session/unit").andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.containsInAnyOrder("ATENDER_PROBLEMA",
                        "OPERAR_COMANDA")));
        // CA-04: sem a permissão, o comando é recusado no servidor.
        assertThatThrownBy(() -> memberships.inUnit(garcom, loja, Permission.CANCELAR_ITEM, m -> true))
                .isInstanceOf(PermissionDeniedException.class);

        Browser gil = login.inUnit(gerente, loja);
        gil.put(permissoes(vinculoGarcom), "{\"permissions\":[\"CANCELAR_ITEM\"]}").andExpect(status().isOk());

        // Mesma sessão do garçom, sem entrar de novo: a próxima operação já vê a permissão.
        gabi.get("/api/v1/session/unit")
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("CANCELAR_ITEM")));
        assertThat(memberships.<Boolean>inUnit(garcom, loja, Permission.CANCELAR_ITEM, m -> true)).isTrue();
        assertThatThrownBy(() -> memberships.inUnit(garcom, loja, Permission.AUTORIZAR_DEVOLUCAO, m -> true))
                .isInstanceOf(PermissionDeniedException.class);

        gil.put(permissoes(vinculoGarcom), "{\"permissions\":[]}").andExpect(status().isOk());
        gabi.get("/api/v1/session/unit")
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                        "CANCELAR_ITEM"))));
        assertThatThrownBy(() -> memberships.inUnit(garcom, loja, Permission.CANCELAR_ITEM, m -> true))
                .isInstanceOf(PermissionDeniedException.class);

        // Histórico: concessão e revogação preservadas, com autoria e o que mudou na auditoria.
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.membership_permission
                 WHERE membership_id = ? AND revoked_at IS NOT NULL AND revoked_by = ? AND granted_by = ?
                """, Integer.class, vinculoGarcom, gerente, gerente)).isEqualTo(1);
        assertThat(admin().queryForList("""
                SELECT action || ':' || detail FROM fastpay.audit_event
                 WHERE establishment_id = ? AND subject_id = ? ORDER BY occurred_at, action
                """, String.class, loja, vinculoGarcom))
                .containsExactly("permissao.concedida:CANCELAR_ITEM", "permissao.revogada:CANCELAR_ITEM");
    }

    @Test
    void desativacaoTiraOAcessoNaSessaoAtivaEPreservaAutoria() throws Exception {
        UUID outraLoja = fixtures.establishment("Outro Bar");
        fixtures.membership(garcom, outraLoja, MembershipRole.GARCOM);
        Browser gabi = login.inUnit(garcom, loja);
        Browser donaRita = login.inUnit(dono, loja);

        donaRita.post("/api/v1/team/members/" + vinculoGarcom + "/deactivate", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.membershipId == '%s')].active".formatted(vinculoGarcom)).value(false));

        gabi.get("/api/v1/session/unit").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
        gabi.get("/api/v1/session/units").andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].establishmentId").value(outraLoja.toString()));
        assertThat(admin().queryForObject("SELECT deactivated_by FROM fastpay.membership WHERE id = ?", UUID.class,
                vinculoGarcom)).isEqualTo(dono);

        donaRita.post("/api/v1/team/members/" + vinculoGarcom + "/deactivate", "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VINCULO_NAO_ENCONTRADO"));
        assertThat(auditoria()).contains("vinculo.desativado");
    }

    @Test
    void promocaoExigeAutenticadorERevogaDelegacoes() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser donaRita = login.inUnit(dono, loja);
        donaRita.put(permissoes(vinculoGarcom), "{\"permissions\":[\"REABRIR_COMANDA\"]}").andExpect(status().isOk());

        donaRita.put("/api/v1/team/members/" + vinculoGarcom + "/role", "{\"role\":\"GERENTE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.membershipId == '%s')].role".formatted(vinculoGarcom)).value("GERENTE"))
                .andExpect(jsonPath("$.members[?(@.membershipId == '%s')].delegated.length()".formatted(vinculoGarcom))
                        .value(0));

        // FR-05: perfil privilegiado configura o autenticador antes da operação seguinte.
        gabi.get("/api/v1/session/unit").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_CADASTRO_OBRIGATORIO"));
        assertThat(admin().queryForList("""
                SELECT action || ':' || detail FROM fastpay.audit_event
                 WHERE establishment_id = ? AND subject_id = ? AND action <> 'permissao.concedida'
                 ORDER BY occurred_at, action
                """, String.class, loja, vinculoGarcom))
                .containsExactly("permissao.revogada:REABRIR_COMANDA", "vinculo.papel_alterado:GARCOM>GERENTE");
    }

    @Test
    void ninguemAlteraOProprioVinculo() throws Exception {
        Browser donaRita = login.inUnit(dono, loja);

        donaRita.put("/api/v1/team/members/" + vinculoDono + "/role", "{\"role\":\"GERENTE\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROPRIO_VINCULO"));
        donaRita.post("/api/v1/team/members/" + vinculoDono + "/deactivate", "")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROPRIO_VINCULO"));
        assertThat(adminsAtivos()).isEqualTo(1);
    }

    @Test
    void administradoresQueSeRebaixamAoMesmoTempoDeixamUmAtivo() throws Exception {
        UUID outroAdmin = fixtures.identity("Otto Admin");
        UUID vinculoOutroAdmin = fixtures.membership(outroAdmin, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        Browser donaRita = login.inUnit(dono, loja);
        Browser otto = login.inUnit(outroAdmin, loja);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tarefas = List.of(
                    () -> {
                        largada.await();
                        return donaRita.put("/api/v1/team/members/" + vinculoOutroAdmin + "/role", "{\"role\":\"GARCOM\"}")
                                .andReturn().getResponse().getStatus();
                    },
                    () -> {
                        largada.await();
                        return otto.put("/api/v1/team/members/" + vinculoDono + "/role", "{\"role\":\"GARCOM\"}")
                                .andReturn().getResponse().getStatus();
                    });
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> tarefa : tarefas) {
                futuros.add(executor.submit(tarefa));
            }
            largada.countDown();
            List<Integer> resultados = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                resultados.add(futuro.get());
            }
            // Quem foi rebaixado primeiro perdeu a autoridade antes de agir: uma troca vale, a outra é recusada.
            assertThat(resultados).containsExactlyInAnyOrder(200, 403);
        } finally {
            executor.shutdownNow();
        }
        assertThat(adminsAtivos()).isEqualTo(1);
    }

    @Test
    void outraLojaNaoEAlcancadaPorId() throws Exception {
        UUID outraLoja = fixtures.establishment("Bar Vizinho");
        UUID vizinho = fixtures.identity("Vera Vizinha");
        UUID vinculoVizinho = fixtures.membership(vizinho, outraLoja, MembershipRole.GARCOM);
        Browser donaRita = login.inUnit(dono, loja);

        donaRita.put("/api/v1/team/members/" + vinculoVizinho + "/role", "{\"role\":\"GERENTE\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VINCULO_NAO_ENCONTRADO"));
        donaRita.post("/api/v1/team/members/" + vinculoVizinho + "/deactivate", "").andExpect(status().isNotFound());
        donaRita.put(permissoes(vinculoVizinho), "{\"permissions\":[\"CANCELAR_ITEM\"]}").andExpect(status().isNotFound());
        donaRita.get("/api/v1/team").andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.membershipId == '%s')]".formatted(vinculoVizinho)).isEmpty());

        assertThat(admin().queryForObject("SELECT role || status FROM fastpay.membership WHERE id = ?", String.class,
                vinculoVizinho)).isEqualTo("GARCOMACTIVE");
        assertThat(delegadas(vinculoVizinho)).isEmpty();
    }

    @Test
    void adminFastPaySemVinculoNaoOperaAEquipe() throws Exception {
        UUID plataforma = fixtures.identity("Bia da fastPay", "ADMIN_FASTPAY");
        Browser bia = login.signIn(plataforma);

        bia.get("/api/v1/team").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
        bia.put("/api/v1/session/unit", "{\"establishmentId\":\"" + loja + "\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
    }

    private int adminsAtivos() {
        return admin().queryForObject("""
                SELECT count(*) FROM fastpay.membership
                 WHERE establishment_id = ? AND role = 'ADMIN_ESTABELECIMENTO' AND status = 'ACTIVE'
                """, Integer.class, loja);
    }

    private List<String> delegadas(UUID vinculo) {
        return admin().queryForList(
                "SELECT permission FROM fastpay.membership_permission WHERE membership_id = ? AND revoked_at IS NULL",
                String.class, vinculo);
    }

    private List<String> auditoria() {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE establishment_id = ?", String.class,
                loja);
    }

    private static String permissoes(UUID vinculo) {
        return "/api/v1/team/members/" + vinculo + "/permissions";
    }

    private static String aceite(String token, String nome) {
        return "{\"token\":\"%s\",\"displayName\":\"%s\",\"password\":\"%s\"}".formatted(token, nome,
                "frase da pessoa nova bem comprida");
    }

    /** Envia os e-mails da fila e devolve o token do último convite para o e-mail. */
    private String token(String email) {
        while (worker.runOnce() > 0) {
            // até não haver mais jobs vencidos
        }
        TestMailbox.Message mensagem = caixa.latestTo(email).orElseThrow();
        Matcher matcher = TOKEN.matcher(mensagem.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
