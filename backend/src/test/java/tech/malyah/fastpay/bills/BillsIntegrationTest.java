package tech.malyah.fastpay.bills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Conta da comanda pela API (H22, FR-28–FR-30, FR-68), com PostgreSQL real, RLS, Spring Session e CSRF. Cobre a
 * validação exigida no backlog: bases do serviço e centavos (a tabela completa está em {@code BillRulesTest}),
 * retirada do serviço, desconto 100%, limite e valores fora do formato, redução do consumo depois da concessão e
 * autorização no servidor; e o aceite: formato e autoridade com motivo, substituição, revisão, couvert automático
 * capturado na abertura e total nunca negativo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class BillsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    JobWorker worker;

    @Autowired
    Bills contas;

    @Autowired
    TenantTransactions tenants;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID vinculoGarcom;
    UUID vinculoGerente;
    UUID batata;
    UUID chopp;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Conta");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        vinculoGerente = fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        batata = produto(loja, "Batata frita", "35.90");
        chopp = produto(loja, "Chopp 300 ml", "9.90");
    }

    @Test
    void composicaoComServicoPadraoERetiradaPeloCliente() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Ana");
        String conta = "/api/v1/tabs/" + comanda + "/bill";

        // Sem pedidos: tudo zero, serviço da configuração capturada (10% após desconto, sem couvert na base).
        gabi.get(conta).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.consumption").value("0.00"))
                .andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.service.enabled").value(true))
                .andExpect(jsonPath("$.service.percent").value("10.0000"))
                .andExpect(jsonPath("$.service.base").value("APOS_DESCONTO"))
                .andExpect(jsonPath("$.discount").value(nullValue()))
                .andExpect(jsonPath("$.policy.canGrant").value(false));

        enviar(gabi, comanda, linha(batata, 2, "35.90"), linha(chopp, 3, "9.90"));
        // C = 71,80 + 29,70 = 101,50; S = 10,15; total 111,65.
        gabi.get(conta).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].productName").value("Batata frita"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("35.90"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value("71.80"))
                .andExpect(jsonPath("$.consumption").value("101.50"))
                .andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.service.amount").value("10.15"))
                .andExpect(jsonPath("$.total").value("111.65"))
                .andExpect(jsonPath("$.reviewPending").value(false));

        // O cliente vê os mesmos valores e retira o serviço sem autorização.
        Browser cliente = vincular(gabi, comanda);
        String guest = "/api/v1/guest/tabs/" + comanda + "/bill";
        cliente.get(guest).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[1].lineTotal").value("29.70"))
                .andExpect(jsonPath("$.total").value("111.65"))
                .andExpect(jsonPath("$.discount").value(nullValue()))
                .andExpect(jsonPath("$.policy").doesNotExist());
        cliente.put(guest + "/service", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("waived"));
        cliente.put(guest + "/service", "{\"waived\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.service.waived").value(true))
                .andExpect(jsonPath("$.service.amount").value("0.00"))
                .andExpect(jsonPath("$.total").value("101.50"));
        // Repetir não grava outra escolha.
        cliente.put(guest + "/service", "{\"waived\":true}").andExpect(status().isOk());
        gabi.get(conta).andExpect(jsonPath("$.service.waived").value(true)).andExpect(jsonPath("$.total")
                .value("101.50"));
        // A equipe devolve a pedido do cliente.
        gabi.put(conta + "/service", "{\"waived\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("111.65"));
        assertThat(auditoria(UUID.fromString(comanda), "servico.%")).containsExactly("servico.retirado",
                "servico.devolvido");
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.tab_service_choice WHERE tab_id = ?",
                Integer.class, UUID.fromString(comanda))).isEqualTo(2);

        // Cliente de outra comanda e quem não opera comanda não veem.
        String outra = comanda(gabi, "Bia");
        cliente.get("/api/v1/guest/tabs/" + outra + "/bill").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMANDA_NAO_ENCONTRADA"));
        cliente.put("/api/v1/guest/tabs/" + outra + "/bill/service", "{\"waived\":true}")
                .andExpect(status().isNotFound());
        new Browser(mvc).get(guest).andExpect(status().isNotFound());
        login.inUnit(producao, loja).get(conta).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        UUID otto = fixtures.identity("Otto Outro");
        fixtures.membership(otto, outraLoja, MembershipRole.GARCOM);
        login.inUnit(otto, outraLoja).get(conta).andExpect(status().isNotFound());

        // Comanda encerrada: a conta não muda mais.
        admin().update("UPDATE fastpay.tab SET closed_at = now() WHERE id = ?", UUID.fromString(comanda));
        cliente.put(guest + "/service", "{\"waived\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMANDA_ENCERRADA"));
        gabi.get(conta).andExpect(status().isOk()).andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void autoridadeFormatoEMotivoDoDescontoNoServidor() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String comanda = comanda(gabi, "Caio");
        String conta = "/api/v1/tabs/" + comanda + "/bill";
        String desconto = conta + "/discount";

        // Sem consumo não há desconto.
        gui.put(desconto, conceder(null, "PERCENTUAL", "10", "Cliente fiel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEM_CONSUMO"));
        enviar(gabi, comanda, linha(batata, 2, "35.90"), linha(chopp, 1, "9.90"), linha(chopp, 1, "9.90"));
        // C = 71,80 + 9,90 + 9,90 = 91,60.

        // O garçom sem a permissão delegada não concede, mesmo com dados inválidos.
        gabi.put(desconto, conceder(null, "FIXO", "abc", "")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        // Delegada, vale já; mas o limite padrão do garçom é 0%.
        delegar(vinculoGarcom, "CONCEDER_DESCONTO");
        gabi.get(conta).andExpect(jsonPath("$.policy.canGrant").value(true))
                .andExpect(jsonPath("$.policy.myLimitPercent").value("0.0000"));
        gabi.put(desconto, conceder(null, "FIXO", "0.01", "Cliente fiel")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DESCONTO_ACIMA_DO_LIMITE"));
        limite(vinculoGarcom, "10");
        // 10% de 91,60 = 9,16: no limite passa; 9,17 não.
        gabi.put(desconto, conceder(null, "FIXO", "9.17", "Cliente fiel")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DESCONTO_ACIMA_DO_LIMITE"));
        gabi.put(desconto, conceder(null, "PERCENTUAL", "10.0001", "Cliente fiel")).andExpect(status().isForbidden());

        // Formato, valor e motivo.
        gabi.put(desconto, conceder(null, "FIXO", "1.001", "Cliente fiel")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("value"));
        gabi.put(desconto, conceder(null, "FIXO", "99999999999999999999", "Cliente fiel"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("value"));
        gabi.put(desconto, conceder(null, "PERCENTUAL", "101", "Cliente fiel")).andExpect(status().isBadRequest());
        gabi.put(desconto, conceder(null, "BRINDE", "1", "Cliente fiel")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("kind"));
        gabi.put(desconto, conceder(null, "FIXO", "5", "    ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        // Não passa do consumo.
        gui.put(desconto, conceder(null, "FIXO", "91.61", "Cortesia da casa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESCONTO_ACIMA_DO_CONSUMO"));

        String primeiro = JsonPath.read(gabi.put(desconto, conceder(null, "FIXO", "9.16", "  Cliente fiel  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount.kind").value("FIXO"))
                .andExpect(jsonPath("$.discount.value").value("9.16"))
                .andExpect(jsonPath("$.discount.amount").value("9.16"))
                .andExpect(jsonPath("$.discount.effectivePercent").value("10.00"))
                .andExpect(jsonPath("$.discount.reason").value("Cliente fiel"))
                .andExpect(jsonPath("$.discount.grantedBy.name").value("Gabi Garçom"))
                .andExpect(jsonPath("$.discount.authorityPercent").value("10.0000"))
                // S = 10% de 82,44 = 8,244 → 8,24; total 91,60 − 9,16 + 8,24 = 90,68.
                .andExpect(jsonPath("$.service.baseAmount").value("82.44"))
                .andExpect(jsonPath("$.service.amount").value("8.24"))
                .andExpect(jsonPath("$.total").value("90.68"))
                .andReturn().getResponse().getContentAsString(), "$.discount.id");

        // Um por comanda: conceder sem conhecer o atual é recusado; conhecendo, substitui.
        gui.put(desconto, conceder(null, "PERCENTUAL", "100", "Aniversariante")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        String segundo = JsonPath.read(gui.put(desconto, conceder(primeiro, "PERCENTUAL", "100", "Aniversariante"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount.amount").value("91.60"))
                .andExpect(jsonPath("$.service.amount").value("0.00"))
                .andExpect(jsonPath("$.total").value("0.00"))
                .andReturn().getResponse().getContentAsString(), "$.discount.id");
        assertThat(admin().queryForObject("SELECT end_kind FROM fastpay.tab_discount WHERE id = ?", String.class,
                UUID.fromString(primeiro))).isEqualTo("SUBSTITUIDO");
        // O cliente vê o desconto, sem motivo nem quem concedeu.
        Browser cliente = vincular(gabi, comanda);
        cliente.get("/api/v1/guest/tabs/" + comanda + "/bill").andExpect(jsonPath("$.discount").value("91.60"))
                .andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        // Retirar exige motivo e o desconto atual; o garçom com 10% pode retirar (desconto menor nunca passa do limite).
        gabi.post(desconto + "/remove", "{\"discountId\":\"" + segundo + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        gabi.post(desconto + "/remove", "{\"discountId\":\"" + primeiro + "\",\"reason\":\"Concedido por engano\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        gabi.post(desconto + "/remove", "{\"discountId\":\"" + segundo + "\",\"reason\":\"Concedido por engano\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(nullValue()))
                .andExpect(jsonPath("$.total").value("100.76"));
        gabi.post(desconto + "/remove", "{\"discountId\":\"" + segundo + "\",\"reason\":\"De novo\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEM_DESCONTO"));

        // Formato fora da configuração vigente (conferida ao conceder).
        novaConfiguracao("discount_fixed_allowed = false");
        gui.put(desconto, conceder(null, "FIXO", "5", "Cortesia da casa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORMATO_NAO_ACEITO"));
        gui.get(conta).andExpect(jsonPath("$.policy.fixedAllowed").value(false));

        assertThat(auditoria(UUID.fromString(primeiro), "desconto.%")).containsExactly("desconto.concedido");
        assertThat(auditoria(UUID.fromString(segundo), "desconto.%")).containsExactly("desconto.concedido",
                "desconto.retirado");
    }

    @Test
    void reducaoDoConsumoMarcaRevisaoEBloqueiaCobranca() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        Browser rita = login.inUnit(dono, loja);
        limite(vinculoGerente, "20");
        delegar(vinculoGarcom, "CONCEDER_DESCONTO");
        limite(vinculoGarcom, "30");
        String comanda = comanda(gabi, "Dani");
        String conta = "/api/v1/tabs/" + comanda + "/bill";
        enviar(gabi, comanda, linha(batata, 2, "35.90"));
        enviar(gabi, comanda, linha(chopp, 3, "9.90"));
        // C = 101,50; 20,30 em reais = 20%.
        String id = JsonPath.read(gui.put(conta + "/discount", conceder(null, "FIXO", "20.30", "Demora no pedido"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewPending").value(false))
                .andReturn().getResponse().getContentAsString(), "$.discount.id");
        assertThat(bloqueia(comanda)).isFalse();
        gui.post(conta + "/discount/review", "{\"discountId\":\"" + id + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESCONTO_SEM_REVISAO"));

        // Cancelamento (H23) ainda não existe: o consumo cai direto no banco, como cairá pela correção autorizada.
        // Sem os 2 chopps: C = 81,70; 20,30 é 24,85%, acima dos 20% de quem concedeu.
        admin().update("""
                UPDATE fastpay.tab_allocation a SET quantity = 1
                  FROM fastpay.order_line l
                 WHERE l.id = a.order_line_id AND a.tab_id = ? AND l.product_name = 'Chopp 300 ml'
                """, UUID.fromString(comanda));
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("81.70"))
                .andExpect(jsonPath("$.discount.amount").value("20.30"))
                .andExpect(jsonPath("$.discount.effectivePercent").value("24.85"))
                .andExpect(jsonPath("$.discount.needsReview").value(true))
                .andExpect(jsonPath("$.reviewPending").value(true));
        assertThat(bloqueia(comanda)).isTrue();

        // Sem a batata: C = 9,90; o desconto em reais efetivo vira o consumo inteiro (100%), total só o serviço zero.
        admin().update("""
                DELETE FROM fastpay.tab_allocation a USING fastpay.order_line l
                 WHERE l.id = a.order_line_id AND a.tab_id = ? AND l.product_name = 'Batata frita'
                """, UUID.fromString(comanda));
        gabi.get(conta).andExpect(jsonPath("$.consumption").value("9.90"))
                .andExpect(jsonPath("$.discount.value").value("20.30"))
                .andExpect(jsonPath("$.discount.amount").value("9.90"))
                .andExpect(jsonPath("$.discount.effectivePercent").value("100.00"))
                .andExpect(jsonPath("$.total").value("0.00"));

        // A correção legítima não é impedida: a revisão só bloqueia a cobrança. Quem revisa precisa de limite que cubra
        // o percentual de agora (100%): o garçom com 30% não; a administradora sim.
        gabi.post(conta + "/discount/review", "{\"discountId\":\"" + id + "\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DESCONTO_ACIMA_DO_LIMITE"));
        rita.post(conta + "/discount/review", "{\"discountId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        rita.post(conta + "/discount/review", "{\"discountId\":\"" + id + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewPending").value(false))
                .andExpect(jsonPath("$.discount.reviewedBy.name").value("Dona Rita"))
                .andExpect(jsonPath("$.discount.authorityPercent").value("100.0000"));
        assertThat(bloqueia(comanda)).isFalse();
        assertThat(auditoria(UUID.fromString(id), "desconto.%")).containsExactly("desconto.concedido",
                "desconto.revisado");

        // Snapshot para fechamento e cobrança (H28/H30): mesma composição, total nunca negativo.
        Bills.Snapshot snapshot = tenants.inEstablishment(loja, () -> contas.snapshotInCurrentUnit(
                UUID.fromString(comanda))).orElseThrow();
        assertThat(snapshot.discountId()).isEqualTo(UUID.fromString(id));
        assertThat(snapshot.composition().total()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.reviewPending()).isFalse();
    }

    @Test
    void couvertAutomaticoCapturadoNaAberturaEServicoDesabilitado() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String antes = comanda(gabi, "Antes");
        enviar(gabi, antes, linha(batata, 1, "35.90"));
        // Couvert automático de 15,00 no serviço, base antes do desconto.
        novaConfiguracao("couvert_enabled = true, couvert_amount = 15.00, service_includes_couvert = true, "
                + "service_base = 'ANTES_DESCONTO', service_percent = 12.5");
        String depois = comanda(gabi, "Depois");
        enviar(gabi, depois, linha(batata, 1, "35.90"));

        // A comanda aberta antes não muda: sem couvert, 10% após desconto.
        gabi.get("/api/v1/tabs/" + antes + "/bill").andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.service.amount").value("3.59")).andExpect(jsonPath("$.total").value("39.49"));
        // A de depois: B = 35,90 + 15,00 = 50,90; S = 12,5% = 6,3625 → 6,36; total 57,26.
        gabi.get("/api/v1/tabs/" + depois + "/bill").andExpect(jsonPath("$.couvert").value("15.00"))
                .andExpect(jsonPath("$.service.percent").value("12.5000"))
                .andExpect(jsonPath("$.service.baseAmount").value("50.90"))
                .andExpect(jsonPath("$.service.amount").value("6.36"))
                .andExpect(jsonPath("$.total").value("57.26"));
        // Desconto nunca é sobre o couvert: 100% zera o consumo, couvert e serviço (base antes do desconto) ficam.
        login.inUnit(gerente, loja).put("/api/v1/tabs/" + depois + "/bill/discount",
                conceder(null, "PERCENTUAL", "100", "Cortesia da casa")).andExpect(status().isOk())
                .andExpect(jsonPath("$.discount.amount").value("35.90"))
                .andExpect(jsonPath("$.total").value("21.36"));

        novaConfiguracao("service_enabled = false, service_percent = 0");
        String semServico = comanda(gabi, "Sem serviço");
        enviar(gabi, semServico, linha(chopp, 1, "9.90"));
        gabi.get("/api/v1/tabs/" + semServico + "/bill").andExpect(jsonPath("$.service.enabled").value(false))
                .andExpect(jsonPath("$.total").value("24.90"));
        gabi.put("/api/v1/tabs/" + semServico + "/bill/service", "{\"waived\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICO_DESABILITADO"));
    }

    @Test
    void duasConcessoesSimultaneasGravamUmDesconto() throws Exception {
        Browser gabi = login.inUnit(garcom, loja);
        String comanda = comanda(gabi, "Eva");
        enviar(gabi, comanda, linha(batata, 1, "35.90"));
        UUID tab = UUID.fromString(comanda);
        List<Bills.Outcome<Bills.Bill>> resultados = emParalelo(List.of(
                () -> contas.grant(gerente, loja, tab, null, "FIXO", "5", "Cliente fiel", null),
                () -> contas.grant(dono, loja, tab, null, "PERCENTUAL", "10", "Aniversariante", null)));
        assertThat(resultados).filteredOn(r -> r instanceof Bills.Done<?>).hasSize(1);
        assertThat(resultados).filteredOn(r -> r instanceof Bills.Refused<?> f
                && f.refusal() == Bills.Refusal.VERSAO_DESATUALIZADA).hasSize(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.tab_discount WHERE tab_id = ?",
                Integer.class, tab)).isEqualTo(1);
    }

    // Apoio

    private boolean bloqueia(String comanda) {
        return tenants.inEstablishment(loja, () -> contas.blocksChargeInCurrentUnit(UUID.fromString(comanda)));
    }

    private List<String> auditoria(UUID sujeito, String padrao) {
        return admin().queryForList("SELECT action FROM fastpay.audit_event WHERE subject_id = ? AND action LIKE ? "
                + "ORDER BY occurred_at, action", String.class, sujeito, padrao);
    }

    private void delegar(UUID vinculo, String permissao) {
        admin().update("""
                INSERT INTO fastpay.membership_permission (id, establishment_id, membership_id, permission, granted_by,
                       granted_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), loja, vinculo, permissao, dono);
    }

    private void limite(UUID vinculo, String percentual) {
        admin().update("UPDATE fastpay.membership SET discount_limit_percent = ?::numeric WHERE id = ?", percentual,
                vinculo);
    }

    /** Nova versão da configuração (H14) a partir da atual, com as mudanças em SQL; vale para comandas novas. */
    private void novaConfiguracao(String mudancas) {
        UUID nova = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.unit_settings_version (id, establishment_id, number, service_enabled,
                       service_percent, service_base, service_includes_couvert, couvert_enabled, couvert_mode,
                       couvert_amount, discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                       created_by, created_at)
                SELECT ?, establishment_id, number + 1, service_enabled, service_percent, service_base,
                       service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                       discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode, ?, now()
                  FROM fastpay.unit_settings_version WHERE establishment_id = ?
                 ORDER BY number DESC LIMIT 1
                """, nova, dono, loja);
        admin().update("UPDATE fastpay.unit_settings_version SET " + mudancas + " WHERE id = ?", nova);
        relogio.advance(Duration.ofSeconds(1));
    }

    private Browser vincular(Browser equipe, String comanda) throws Exception {
        String link = JsonPath.read(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.link");
        Browser cliente = new Browser(mvc);
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + link.substring(link.indexOf('#') + 1)
                + "\"}").andExpect(status().isOk());
        return cliente;
    }

    private static String conceder(String atual, String tipo, String valor, String motivo) {
        return "{\"currentDiscountId\":" + (atual == null ? "null" : "\"" + atual + "\"") + ",\"kind\":\"" + tipo
                + "\",\"value\":\"" + valor + "\",\"reason\":\"" + motivo + "\"}";
    }

    private String comanda(Browser quem, String nome) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void enviar(Browser quem, String comanda, String... linhas) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        StringJoiner itens = new StringJoiner(",", "[", "]");
        for (String l : linhas) {
            itens.add(l);
        }
        quem.post("/api/v1/orders", "{\"tabId\":\"" + comanda + "\",\"operationKey\":\"" + UUID.randomUUID()
                + "\",\"lines\":" + itens + "}").andExpect(status().isOk());
        while (worker.runOnce() > 0) {
            // até esvaziar
        }
    }

    private UUID produto(UUID unidade, String nome, String preco) {
        UUID id = UUID.randomUUID();
        admin().update("""
                INSERT INTO fastpay.product (id, establishment_id, name, category, price, sector_id, created_by,
                                             created_at, updated_by, updated_at)
                VALUES (?, ?, ?, 'Geral', ?::numeric, NULL, ?, now(), ?, now())
                """, id, unidade, nome, preco, dono, dono);
        return id;
    }

    private static String linha(UUID produto, int quantidade, String preco) {
        return "{\"productId\":\"" + produto + "\",\"quantity\":" + quantidade + ",\"note\":null,\"expectedPrice\":\""
                + preco + "\"}";
    }

    private static <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return tarefa.call();
                }));
            }
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }
}
