package tech.malyah.fastpay.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.application.OperatorCommands;
import tech.malyah.fastpay.identity.application.OperatorCommands.IssuedLink;
import tech.malyah.fastpay.identity.application.OperatorCommands.Refused;
import tech.malyah.fastpay.identity.domain.Base32;
import tech.malyah.fastpay.identity.domain.Totp;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.mail.infrastructure.TestMailbox;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Comandos de operador (H09, T-05, P-07): bootstrap do primeiro admin fastPay sem cadastro público nem
 * credencial padrão, com MFA obrigatório no primeiro acesso, e recuperação excepcional do MFA do único
 * admin. Rodam com a credencial de migração, como no comando de linha; o runtime não consegue fazer o
 * mesmo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OperatorCommandsIntegrationTest.RelogioControlado.class)
class OperatorCommandsIntegrationTest extends PostgresIntegrationTest {

    static final String SENHA = "frase longa do admin da plataforma";
    static final String LINK_BASE = "http://127.0.0.1:8088/redefinir-senha";

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
    MfaSecretCipher cifra;

    @Autowired
    JdbcTemplate runtime;

    @Autowired
    TestMailbox caixa;

    @Autowired
    JobWorker worker;

    OperatorCommands operador;
    MfaFixtures mfa;

    @BeforeEach
    void semAdminFastPay() {
        operador = new OperatorCommands(dataSource(POSTGRES.getJdbcUrl(), "fastpay_migration", migrationPassword()),
                LINK_BASE, relogio, 5);
        mfa = new MfaFixtures(admin(), cifra);
        // O banco de teste é compartilhado: admins criados por outras classes deixam de ser admin aqui.
        admin().update("UPDATE fastpay.user_identity SET platform_role = NULL WHERE platform_role = 'ADMIN_FASTPAY'");
    }

    @Test
    void bootstrapCriaOPrimeiroAdminSemSenhaQueDefineSenhaEPrecisaCadastrarMfa() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@teste.invalid";

        IssuedLink link = operador.createPlatformAdmin(" " + email.toUpperCase() + " ", "Admin fastPay", "Diego",
                "Primeiro acesso da plataforma");

        assertThat(link.link()).startsWith(LINK_BASE + "#token=");
        assertThat(link.expiresAt()).isEqualTo(relogio.instant().plus(Duration.ofMinutes(30)));
        Map<String, Object> identidade = admin().queryForMap(
                "SELECT email, platform_role FROM fastpay.user_identity WHERE id = ?", link.identityId());
        assertThat(identidade).containsEntry("email", email).containsEntry("platform_role", "ADMIN_FASTPAY");
        // Sem credencial padrão: só o link permite criar a senha.
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_credential WHERE identity_id = ?",
                Integer.class, link.identityId())).isZero();
        assertThat(admin().queryForMap("SELECT action, actor, reason FROM fastpay.security_event WHERE identity_id = ?",
                link.identityId())).containsEntry("action", "admin_fastpay.criado")
                .containsEntry("actor", "operador:Diego").containsEntry("reason", "Primeiro acesso da plataforma");

        definirSenha(link).andExpect(status().isNoContent());
        Browser navegador = new Browser(mvc);
        navegador.login(email, SENHA).andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"));
        navegador.get("/api/v1/session/units").andExpect(status().isForbidden());
        cadastrarAutenticador(navegador).andExpect(jsonPath("$.session.idleTimeoutSeconds").value(900));
        navegador.get("/api/v1/session/units").andExpect(status().isOk());
    }

    @Test
    void bootstrapSoCriaOPrimeiroENaoReaproveitaEmailExistente() {
        operador.createPlatformAdmin("admin-" + UUID.randomUUID() + "@teste.invalid", "Admin", "Diego",
                "Primeiro acesso da plataforma");

        assertThatThrownBy(() -> operador.createPlatformAdmin("outro-" + UUID.randomUUID() + "@teste.invalid", "Outro",
                "Diego", "Segundo admin pelo bootstrap")).isInstanceOf(Refused.class).hasMessageContaining("Já existe");

        admin().update("UPDATE fastpay.user_identity SET platform_role = NULL WHERE platform_role = 'ADMIN_FASTPAY'");
        UUID gerente = new IdentityFixtures(admin()).identity("Gerente");
        assertThatThrownBy(() -> operador.createPlatformAdmin(IdentityFixtures.email(gerente), "Gerente", "Diego",
                "Promover gerente a admin")).isInstanceOf(Refused.class).hasMessageContaining("já tem uma identidade");
    }

    @Test
    void comandosValidamEmailNomeOperadorEMotivo() {
        assertThatThrownBy(() -> operador.createPlatformAdmin("sem-arroba", "Admin", "Diego", "Motivo suficiente"))
                .hasMessage("Informe um e-mail válido.");
        assertThatThrownBy(() -> operador.createPlatformAdmin("a@b.c", " ", "Diego", "Motivo suficiente"))
                .isInstanceOf(Refused.class);
        assertThatThrownBy(() -> operador.createPlatformAdmin("a@b.c", "Admin", "", "Motivo suficiente"))
                .isInstanceOf(Refused.class);
        assertThatThrownBy(() -> operador.resetMfa("a@b.c", "Diego", "curto")).hasMessageContaining("motivo");
        assertThatThrownBy(() -> operador.resetMfa("ninguem-" + UUID.randomUUID() + "@teste.invalid", "Diego",
                "Celular perdido e sem códigos")).hasMessage("Nenhuma identidade com este e-mail.");
    }

    @Test
    void recuperacaoExcepcionalDoUnicoAdminTiraMfaESenhaEncerraSessoesERegistra() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@teste.invalid";
        IssuedLink criado = operador.createPlatformAdmin(email, "Admin", "Diego", "Primeiro acesso da plataforma");
        UUID adminId = criado.identityId();
        definirSenha(criado);
        Browser sessaoAntiga = new Browser(mvc);
        sessaoAntiga.login(email, SENHA);
        cadastrarAutenticador(sessaoAntiga).andExpect(status().isOk());
        relogio.advance(Duration.ofMinutes(1));
        caixa.clear();

        // Celular e códigos perdidos; identidade confirmada fora do sistema.
        IssuedLink link = operador.resetMfa(email, "Diego", "Celular roubado, sem códigos; identidade confirmada");

        // Aviso durável ao e-mail da conta, gravado junto com a recuperação; o link nunca vai nele.
        while (worker.runOnce() > 0) {
            // até não haver mais jobs vencidos
        }
        assertThat(caixa.messages()).filteredOn(m -> m.to().equals(email)).singleElement().satisfies(aviso -> {
            assertThat(aviso.subject()).endsWith("Acesso à sua conta redefinido no fastPay");
            assertThat(aviso.body()).contains("Olá, Admin", "deixaram de valer", "este e-mail não traz link")
                    .doesNotContain("#token=");
        });

        sessaoAntiga.get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        new Browser(mvc).login(email, SENHA).andExpect(status().isUnauthorized());
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.user_mfa WHERE identity_id = ?", Integer.class,
                adminId)).isZero();
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.mfa_recovery_code
                 WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Integer.class, adminId)).isZero();
        assertThat(admin().queryForList("SELECT action FROM fastpay.security_event WHERE identity_id = ? ORDER BY occurred_at",
                String.class, adminId)).containsExactly("admin_fastpay.criado", "mfa.cadastrado",
                "mfa.recuperacao_excepcional");
        // O link anterior (do bootstrap) já foi usado; o novo é o único que vale.
        definirSenha(criado).andExpect(status().isBadRequest());

        definirSenha(link).andExpect(status().isNoContent());
        Browser navegador = new Browser(mvc);
        navegador.login(email, SENHA).andExpect(jsonPath("$.stage").value("MFA_CADASTRO_OBRIGATORIO"));
        cadastrarAutenticador(navegador).andExpect(status().isOk());
    }

    @Test
    void runtimeNaoCriaAdminNemRemoveMfa() {
        UUID ana = new IdentityFixtures(admin()).identity("Ana");
        mfa.enroll(ana);

        assertThatThrownBy(() -> runtime.update(
                "INSERT INTO user_identity (id, email, display_name, platform_role) VALUES (?, ?, 'X', 'ADMIN_FASTPAY')",
                UUID.randomUUID(), UUID.randomUUID() + "@teste.invalid")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("DELETE FROM user_mfa WHERE identity_id = ?", ana))
                .rootCause().hasMessageContaining("permission denied");
        // Sem contexto da identidade, o runtime não vê autenticador nem códigos de ninguém.
        assertThat(runtime.queryForObject("SELECT count(*) FROM user_mfa", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM mfa_recovery_code", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM security_event", Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions definirSenha(IssuedLink link) throws Exception {
        String token = link.link().substring(link.link().indexOf("#token=") + 7);
        return new Browser(mvc).post("/api/v1/auth/password-reset/confirm",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + SENHA + "\"}");
    }

    private org.springframework.test.web.servlet.ResultActions cadastrarAutenticador(Browser navegador) throws Exception {
        String secret = JsonPath.read(navegador.post("/api/v1/auth/mfa/setup", "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.secret");
        String codigo = Totp.code(Base32.decode(secret), Totp.step(relogio.instant()));
        return navegador.post("/api/v1/auth/mfa/setup/confirm", "{\"code\":\"" + codigo + "\"}");
    }
}
