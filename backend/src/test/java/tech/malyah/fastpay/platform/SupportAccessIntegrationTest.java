package tech.malyah.fastpay.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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
 * Suporte autorizado pelo dono (H11, FR-66, CA-28), com PostgreSQL real, RLS e relógio controlado: só o dono
 * concede, a uma pessoa da equipe fastPay, com motivo e prazo; o suporte consulta só a unidade autorizada, só leitura
 * e com a própria autoria na auditoria; sem concessão, vencida ou encerrada, recusa; o suporte não vira funcionário.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class SupportAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    SupportAccess suporte;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID bia;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar com Suporte");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gil Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        bia = fixtures.identity("Bia do Suporte", "ADMIN_FASTPAY");
    }

    @Test
    void donoConcedeESuporteConsultaSoLeituraComAutoriaPropria() throws Exception {
        Browser donaRita = login.inUnit(dono, loja);
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia).toUpperCase(), 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHours").value(72))
                .andExpect(jsonPath("$.grants[0].supportEmail").value(IdentityFixtures.email(bia)))
                .andExpect(jsonPath("$.grants[0].status").value("ATIVA"))
                .andExpect(jsonPath("$.grants[0].expiresAt").value(relogio.instant().plus(Duration.ofHours(4)).toString()));

        Browser suporteBia = login.signIn(bia);
        suporteBia.get("/api/v1/support/grants").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].establishmentId").value(loja.toString()))
                .andExpect(jsonPath("$[0].establishmentName").value("Bar com Suporte"))
                .andExpect(jsonPath("$[0].reason").value("Conferir convites que não chegaram"));
        suporteBia.get("/api/v1/support/units/" + loja + "/team").andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3));

        // Auditoria da unidade: concessão pelo dono, consulta com a autoria do suporte e o que foi consultado.
        assertThat(admin().queryForList("""
                SELECT action || ':' || actor_id || ':' || coalesce(detail, '') FROM fastpay.audit_event
                 WHERE establishment_id = ? AND action LIKE 'suporte.%' ORDER BY occurred_at, action
                """, String.class, loja))
                .containsExactly("suporte.concedido:" + dono + ":4h", "suporte.consultado:" + bia + ":equipe");
        donaRita.get("/api/v1/support-grants").andExpect(jsonPath("$.grants[0].consultations").value(1));

        // Suporte não vira funcionário: não escolhe a unidade nem opera a equipe.
        suporteBia.put("/api/v1/session/unit", "{\"establishmentId\":\"" + loja + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_AUTORIZADA"));
        suporteBia.post("/api/v1/team/invitations", "{\"email\":\"x@teste.invalid\",\"role\":\"GARCOM\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.membership WHERE identity_id = ?", Integer.class,
                bia)).isZero();
    }

    @Test
    void consultaDoSuporteRodaSomenteLeituraNoBanco() {
        concederDireto(bia, loja, Duration.ofHours(2));

        assertThatThrownBy(() -> suporte.consult(bia, loja, "teste", () -> jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action) VALUES (?, current_establishment_id(), 'teste.escrita')
                """, UUID.randomUUID()))).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
        assertThat(suporte.consult(bia, loja, "teste", () -> jdbc.queryForObject(
                "SELECT count(*) FROM membership", Integer.class))).isEqualTo(3);
        assertThat(admin().queryForList("SELECT action FROM fastpay.audit_event WHERE establishment_id = ?",
                String.class, loja)).containsOnly("suporte.consultado").hasSize(2);
    }

    @Test
    void semConcessaoVigenteNaUnidadeOSuporteERecusado() throws Exception {
        UUID outraLoja = fixtures.establishment("Bar sem Suporte");
        Browser donaRita = login.inUnit(dono, loja);
        Browser suporteBia = login.signIn(bia);

        // Sem concessão, unidade de outra loja ou inexistente: mesma recusa.
        for (UUID unidade : List.of(loja, outraLoja, UUID.randomUUID())) {
            suporteBia.get("/api/v1/support/units/" + unidade + "/team").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SUPORTE_NAO_AUTORIZADO"));
        }

        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 1)).andExpect(status().isOk());
        suporteBia.get("/api/v1/support/units/" + loja + "/team").andExpect(status().isOk());
        suporteBia.get("/api/v1/support/units/" + outraLoja + "/team").andExpect(status().isForbidden());

        // Encerrada pelo dono antes do prazo.
        String concessao = JsonPath.read(donaRita.get("/api/v1/support-grants").andReturn().getResponse()
                .getContentAsString(), "$.grants[0].id");
        donaRita.post("/api/v1/support-grants/" + concessao + "/revoke", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[0].status").value("ENCERRADA"));
        suporteBia.get("/api/v1/support/units/" + loja + "/team").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPORTE_NAO_AUTORIZADO"));
        suporteBia.get("/api/v1/support/grants").andExpect(jsonPath("$.length()").value(0));
        donaRita.post("/api/v1/support-grants/" + concessao + "/revoke", "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCESSAO_NAO_ENCONTRADA"));
        assertThat(admin().queryForList("SELECT action FROM fastpay.audit_event WHERE establishment_id = ?",
                String.class, loja)).contains("suporte.encerrado");

        // Vencida pelo prazo.
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 1)).andExpect(status().isOk());
        assertThat(suporte.<Boolean>consult(bia, loja, "teste", () -> true)).isTrue();
        relogio.advance(Duration.ofMinutes(61));
        assertThatThrownBy(() -> suporte.consult(bia, loja, "teste", () -> true))
                .isInstanceOf(SupportNotAuthorizedException.class);
        assertThat(suporte.grants(dono, loja)).extracting(SupportAccess.GrantView::status)
                .containsExactly(SupportAccess.Status.VENCIDA, SupportAccess.Status.ENCERRADA);
    }

    @Test
    void suporteQueDeixaDeSerAdminFastPayPerdeOAcesso() {
        concederDireto(bia, loja, Duration.ofHours(2));
        assertThat(suporte.<Boolean>consult(bia, loja, "teste", () -> true)).isTrue();

        admin().update("UPDATE fastpay.user_identity SET platform_role = NULL WHERE id = ?", bia);

        assertThatThrownBy(() -> suporte.consult(bia, loja, "teste", () -> true))
                .isInstanceOf(SupportNotAuthorizedException.class);
    }

    @Test
    void soODonoConcedeEOEmailPrecisaSerDaEquipeFastPay() throws Exception {
        login.inUnit(gerente, loja).post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 2))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        login.inUnit(garcom, loja).get("/api/v1/support-grants")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        Browser donaRita = login.inUnit(dono, loja);
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(gerente), 2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NAO_E_SUPORTE"));
        donaRita.post("/api/v1/support-grants", concessao("ninguem@teste.invalid", 2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NAO_E_SUPORTE"));
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 73))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRAZO_INVALIDO"));
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRAZO_INVALIDO"));
        donaRita.post("/api/v1/support-grants",
                "{\"supportEmail\":\"%s\",\"reason\":\"ok\",\"hours\":2}".formatted(IdentityFixtures.email(bia)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MOTIVO_INVALIDO"));

        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 72)).andExpect(status().isOk());
        donaRita.post("/api/v1/support-grants", concessao(IdentityFixtures.email(bia), 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPORTE_JA_AUTORIZADO"));

        // A lista do suporte é só para a equipe fastPay.
        login.inUnit(gerente, loja).get("/api/v1/support/grants").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACESSO_RESTRITO_PLATAFORMA"));
    }

    private void concederDireto(UUID suporte, UUID unidade, Duration prazo) {
        admin().update("""
                INSERT INTO fastpay.support_grant (id, establishment_id, support_identity_id, support_email, reason,
                                                   granted_by, granted_at, expires_at)
                VALUES (?, ?, ?, ?, 'Teste de consulta', ?, ?, ?)
                """, UUID.randomUUID(), unidade, suporte, IdentityFixtures.email(suporte), dono,
                java.sql.Timestamp.from(relogio.instant()), java.sql.Timestamp.from(relogio.instant().plus(prazo)));
    }

    private static String concessao(String email, int horas) {
        return "{\"supportEmail\":\"%s\",\"reason\":\"Conferir convites que não chegaram\",\"hours\":%d}"
                .formatted(email, horas);
    }
}
