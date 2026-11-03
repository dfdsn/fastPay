package tech.malyah.fastpay.establishments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Configuração versionada pela API (H14), com PostgreSQL real, RLS, Spring Session e CSRF. Cobre a validação exigida
 * no backlog: versão capturada antes e depois de uma mudança continua a mesma, permissão alterada vale na hora,
 * limite percentual e configuração inválida recusados; e corte/fuso com vigência futura (FR-57, CA-26).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class SettingsIntegrationTest extends PostgresIntegrationTest {

    static final String URL = "/api/v1/establishment/settings";
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    Memberships memberships;

    @Autowired
    EstablishmentSettings settings;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID vinculoGerente;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Configuração");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dora Dona");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gina Garçom");
        producao = fixtures.identity("Pedro Produção");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
    }

    @Test
    void unidadeNasceComOsPadroesEGerenteConsultaSemEditar() throws Exception {
        login.inUnit(dono, loja).get(URL).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.serviceEnabled").value(true))
                .andExpect(jsonPath("$.servicePercent").value("10.0000"))
                .andExpect(jsonPath("$.serviceBase").value("APOS_DESCONTO"))
                .andExpect(jsonPath("$.serviceIncludesCouvert").value(false))
                .andExpect(jsonPath("$.couvertEnabled").value(false))
                .andExpect(jsonPath("$.couvertAmount").value(nullValue()))
                .andExpect(jsonPath("$.discountFixedAllowed").value(true))
                .andExpect(jsonPath("$.discountPercentAllowed").value(true))
                .andExpect(jsonPath("$.attendanceMode").value("GARCOM_RESPONSAVEL"))
                .andExpect(jsonPath("$.exitMode").value("AUTOMATICA"))
                .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
                .andExpect(jsonPath("$.cutoff").value("06:00"))
                .andExpect(jsonPath("$.pendingCalendar").value(nullValue()))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].author").value(nullValue()))
                .andExpect(jsonPath("$.editable").value(true));
        Browser gui = login.inUnit(gerente, loja);
        gui.get(URL).andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(false));
        gui.put(URL, corpo(1, "12", true, "15,00", "06:00", "America/Sao_Paulo"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        for (UUID semAcesso : List.of(garcom, producao)) {
            Browser b = login.inUnit(semAcesso, loja);
            b.get(URL).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
            b.put(URL, corpo(1, "12", true, "15,00", "06:00", "America/Sao_Paulo"))
                    .andExpect(status().isForbidden());
        }
        assertThat(versoes(loja)).isOne();
    }

    @Test
    void salvarCriaNovaVersaoEAComandaCapturadaAntesContinuaComAVersaoAntiga() throws Exception {
        // Abertura de uma comanda (H15) antes da mudança: guarda o id da versão vigente.
        SettingsVersion antes = memberships.inUnit(dono, loja, me -> settings.currentInCurrentUnit());
        assertThat(antes.number()).isEqualTo(1);

        Browser dora = login.inUnit(dono, loja);
        String resposta = dora.put(URL, corpo(1, "12,5", true, "R$ 15,00", "06:00", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.servicePercent").value("12.5000"))
                .andExpect(jsonPath("$.couvertEnabled").value(true))
                .andExpect(jsonPath("$.couvertMode").value("MANUAL"))
                .andExpect(jsonPath("$.couvertAmount").value("15.00"))
                .andExpect(jsonPath("$.exitMode").value("VALIDADA"))
                .andExpect(jsonPath("$.history[0].version").value(2))
                .andExpect(jsonPath("$.history[0].author").value("Dora Dona"))
                .andExpect(jsonPath("$.history[0].changed").value(org.hamcrest.Matchers.contains("servico", "couvert",
                        "saida")))
                .andReturn().getResponse().getContentAsString();
        UUID v2 = UUID.fromString(JsonPath.read(resposta, "$.versionId"));

        // Comanda aberta depois captura a versão 2; a anterior continua lendo a versão 1, intacta.
        SettingsVersion depois = memberships.inUnit(garcom, loja, me -> settings.currentInCurrentUnit());
        assertThat(depois.id()).isEqualTo(v2);
        SettingsVersion relida = memberships.inUnit(garcom, loja,
                me -> settings.versionInCurrentUnit(antes.id()).orElseThrow());
        assertThat(relida).isEqualTo(antes);
        assertThat(relida.values().servicePercent()).isEqualByComparingTo("10.00");
        assertThat(relida.values().couvertEnabled()).isFalse();
        assertThat(relida.values().exitMode()).isEqualTo(ExitMode.AUTOMATICA);
        // Outra unidade não lê a versão desta.
        java.util.Optional<SettingsVersion> nesta = memberships.inUnit(dono, loja,
                me -> settings.versionInCurrentUnit(v2));
        assertThat(nesta).isPresent();
        UUID donoDaOutra = fixtures.identity("Olga Outra");
        fixtures.membership(donoDaOutra, outraLoja, MembershipRole.ADMIN_ESTABELECIMENTO);
        java.util.Optional<SettingsVersion> naOutra = memberships.inUnit(donoDaOutra, outraLoja,
                me -> settings.versionInCurrentUnit(v2));
        assertThat(naOutra).isEmpty();

        // Salvar os mesmos valores não cria versão.
        dora.put(URL, corpo(2, "12.50", true, "15", "06:00", "America/Sao_Paulo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(versoes(loja)).isEqualTo(2);
        assertThat(auditoria(loja, "configuracao.%")).containsExactly("configuracao.alterada");
        assertThat(admin().queryForObject("""
                SELECT detail FROM fastpay.audit_event WHERE establishment_id = ? AND action = 'configuracao.alterada'
                """, String.class, loja)).isEqualTo("v2: servico,couvert,saida");
    }

    @Test
    void versaoDesatualizadaEConfiguracaoInvalidaSaoRecusadas() throws Exception {
        Browser dora = login.inUnit(dono, loja);
        dora.put(URL, corpo(1, "11", false, null, "06:00", "America/Sao_Paulo")).andExpect(status().isOk());
        // Tela aberta antes da versão 2.
        dora.put(URL, corpo(1, "15", false, null, "06:00", "America/Sao_Paulo"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));

        dora.put(URL, corpo(2, "20.01", false, null, "06:00", "America/Sao_Paulo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.field").value("servicePercent"));
        dora.put(URL, corpo(2, "10", true, null, "13:00", "-03:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("couvertAmount"))
                .andExpect(jsonPath("$.problems[*].field").value(org.hamcrest.Matchers.contains("couvertAmount",
                        "timeZone", "cutoff")));
        dora.put(URL, "{\"serviceEnabled\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUISICAO_INVALIDA"));
        // Inválido sem permissão: 403, sem revelar a validação.
        login.inUnit(gerente, loja).put(URL, corpo(2, "99", false, null, "06:00", "America/Sao_Paulo"))
                .andExpect(status().isForbidden());
        assertThat(versoes(loja)).isEqualTo(2);

        // O banco também recusa fora dos limites, mesmo sem passar pela aplicação.
        assertThatThrownBy(() -> admin().update("""
                INSERT INTO fastpay.unit_settings_version (id, establishment_id, number, service_enabled,
                       service_percent, service_base, service_includes_couvert, couvert_enabled, couvert_mode,
                       couvert_amount, discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                       created_by, created_at)
                VALUES (gen_random_uuid(), ?, 3, true, 25, 'APOS_DESCONTO', false, false, 'AUTOMATICO', NULL, true,
                        true, 'CENTRAL', 'AUTOMATICA', ?, now())
                """, loja, dono)).hasMessageContaining("check");
    }

    @Test
    void permissaoAlteradaValeNaOperacaoSeguinte() throws Exception {
        Browser gui = login.inUnit(gerente, loja);
        gui.get(URL).andExpect(status().isOk());
        admin().update("UPDATE fastpay.membership SET role = 'GARCOM' WHERE id = ?", vinculoGerente);
        gui.get(URL).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        admin().update("UPDATE fastpay.membership SET role = 'ADMIN_ESTABELECIMENTO' WHERE id = ?", vinculoGerente);
        gui.put(URL, corpo(1, "10", false, null, "06:00", "America/Sao_Paulo")).andExpect(status().isOk());
    }

    @Test
    void mudancaDeCorteValeSoNoProximoDiaOperacional() throws Exception {
        Instant agora = relogio.instant();
        OperationalDay hoje = memberships.inUnit(dono, loja, me -> settings.dayInCurrentUnit(agora));
        assertThat(hoje.cutoff()).isEqualTo(LocalTime.of(6, 0));

        Browser dora = login.inUnit(dono, loja);
        String resposta = dora.put(URL, corpo(1, "10", false, null, "08:00", "America/Sao_Paulo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.cutoff").value("08:00"))
                .andExpect(jsonPath("$.calendarInUse.cutoff").value("06:00"))
                .andExpect(jsonPath("$.pendingCalendar.cutoff").value("08:00"))
                .andExpect(jsonPath("$.pendingCalendar.firstDate").value(hoje.date().plusDays(1).toString()))
                .andExpect(jsonPath("$.today.date").value(hoje.date().toString()))
                .andReturn().getResponse().getContentAsString();
        Instant inicio = Instant.parse(JsonPath.read(resposta, "$.pendingCalendar.effectiveFrom"));
        // Vale no início do próximo dia pelo corte antigo (06:00 local), dentro das próximas 24 h.
        assertThat(inicio).isEqualTo(hoje.end()).isAfter(agora).isBeforeOrEqualTo(agora.plus(Duration.ofHours(24)));
        assertThat(inicio.atZone(SP).toLocalTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(auditoria(loja, "calendario.%")).containsExactly("calendario.mudanca_agendada");

        // Trocar de ideia antes de valer substitui a mudança pendente; voltar ao corte em uso a cancela.
        dora.put(URL, corpo(1, "10", false, null, "07:00", "America/Sao_Paulo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCalendar.cutoff").value("07:00"));
        dora.put(URL, corpo(1, "10", false, null, "06:00", "America/Sao_Paulo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCalendar").value(nullValue()));
        dora.put(URL, corpo(1, "10", false, null, "08:00", "America/Sao_Paulo")).andExpect(status().isOk());
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.operational_calendar WHERE establishment_id = ? AND canceled_at IS NULL
                """, Integer.class, loja)).isEqualTo(2);

        // Depois que vale: o dia de hoje continua classificado pelo corte antigo, o seguinte pelo novo.
        relogio.advance(Duration.between(agora, inicio).plusHours(30));
        Instant depois = relogio.instant();
        dora = login.inUnit(dono, loja);
        dora.get(URL).andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarInUse.cutoff").value("08:00"))
                .andExpect(jsonPath("$.pendingCalendar").value(nullValue()));
        OperationalDay antigo = memberships.inUnit(dono, loja, me -> settings.dayInCurrentUnit(agora));
        assertThat(antigo).isEqualTo(hoje);
        OperationalDay novo = memberships.inUnit(dono, loja, me -> settings.dayInCurrentUnit(depois));
        assertThat(novo.cutoff()).isEqualTo(LocalTime.of(8, 0));
        assertThat(novo.date()).isAfter(hoje.date());
        // Já em vigor, a mudança não pode mais ser cancelada; uma nova vale no próximo dia.
        dora.put(URL, corpo(1, "10", false, null, "06:00", "America/Sao_Paulo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarInUse.cutoff").value("08:00"))
                .andExpect(jsonPath("$.pendingCalendar.cutoff").value("06:00"));
    }

    @Test
    void runtimeNaoAlteraVersaoGravadaNemLeSemContexto() throws Exception {
        login.inUnit(dono, loja).put(URL, corpo(1, "11", false, null, "06:00", "America/Sao_Paulo"))
                .andExpect(status().isOk());
        JdbcTemplate runtime = new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl(), "fastpay_app", appPassword()));
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.unit_settings_version", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.operational_calendar", Integer.class)).isZero();
        assertThatThrownBy(() -> runtime.update("UPDATE fastpay.unit_settings_version SET service_percent = 1"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("DELETE FROM fastpay.unit_settings_version"))
                .rootCause().hasMessageContaining("permission denied");
    }

    private static String corpo(int version, String percent, boolean couvert, String couvertAmount, String cutoff,
            String zone) {
        return """
                {"version":%d,"serviceEnabled":true,"servicePercent":"%s","serviceBase":"APOS_DESCONTO",
                 "serviceIncludesCouvert":false,"couvertEnabled":%s,"couvertMode":"%s","couvertAmount":%s,
                 "discountFixedAllowed":true,"discountPercentAllowed":true,"attendanceMode":"GARCOM_RESPONSAVEL",
                 "exitMode":"%s","timeZone":"%s","cutoff":"%s"}
                """.formatted(version, percent, couvert, couvert ? "MANUAL" : "AUTOMATICO",
                couvertAmount == null ? "null" : "\"" + couvertAmount + "\"", couvert ? "VALIDADA" : "AUTOMATICA",
                zone, cutoff);
    }

    private static int versoes(UUID loja) {
        return admin().queryForObject("SELECT count(*) FROM fastpay.unit_settings_version WHERE establishment_id = ?",
                Integer.class, loja);
    }

    private static List<String> auditoria(UUID loja, String like) {
        return admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE establishment_id = ? AND action LIKE ?
                 ORDER BY occurred_at
                """, String.class, loja, like);
    }
}
