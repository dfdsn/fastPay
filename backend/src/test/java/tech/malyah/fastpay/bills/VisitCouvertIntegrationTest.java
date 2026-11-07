package tech.malyah.fastpay.bills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

import tech.malyah.fastpay.attendance.ConsumerIssues;
import tech.malyah.fastpay.bills.domain.CouvertRules.Reason;
import tech.malyah.fastpay.establishments.EstablishmentSettings;
import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Continuação de visita e ajustes de couvert pela API (H26, FR-12, FR-29, FR-68, CA-13, D-12), com PostgreSQL real,
 * RLS, Spring Session e CSRF. Cobre a validação exigida no backlog: virada do dia, segunda comanda, nomes iguais,
 * devolução com motivo distinto simulada pelo contrato da H35 e ausência de recobrança implícita; e o aceite: vínculo
 * só por quem tem a permissão e com a permanência confirmada, couvert automático/manual com o valor certo, dispensa
 * por indevido/isenção, duplicidade preservando o válido, correção com inclusão explícita e saída validada encerrando
 * a continuidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class VisitCouvertIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    @Autowired
    Bills contas;

    @Autowired
    Tabs comandas;

    @Autowired
    ConsumerIssues problemas;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    EstablishmentSettings configuracao;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID vinculoGarcom;

    @BeforeEach
    void montarLoja() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar da Visita");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gui Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
    }

    @Test
    void vinculoSoComPermissaoEConfirmacaoENuncaPeloNome() throws Exception {
        couvertAutomatico("15.00");
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String primeira = comanda(gabi, "Ana");
        String outraAna = comanda(gabi, "Ana");
        String segunda = comanda(gabi, "Ana");

        // Nomes iguais não juntam visitas: cada comanda nasce na sua e com o seu couvert automático.
        assertThat(visita(primeira)).isNotEqualTo(visita(outraAna)).isNotEqualTo(visita(segunda));
        gabi.get(conta(segunda)).andExpect(jsonPath("$.couvert").value("15.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO"))
                .andExpect(jsonPath("$.couvertDetail.origin").value("AUTOMATICO"));

        // Garçom sem delegação não vincula nem lista (403 antes de validar).
        gabi.get(candidatas(segunda)).andExpect(status().isForbidden());
        gabi.post(continuar(segunda), vinculo(primeira, true, versao(gabi, segunda)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        // A lista vem por hora de abertura (mais recente primeiro), sem filtrar nem ordenar pelo nome.
        gui.get(candidatas(segunda)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(outraAna))
                .andExpect(jsonPath("$[1].id").value(primeira))
                .andExpect(jsonPath("$[1].name").value("Ana"));

        // Sem confirmar a permanência, recusa.
        gui.post(continuar(segunda), vinculo(primeira, false, versao(gui, segunda)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stayConfirmed"));
        // Comanda anterior aberta depois, inexistente ou a própria: recusa.
        gui.post(continuar(primeira), vinculo(segunda, true, versao(gui, primeira)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ANTERIOR_INVALIDA"));
        gui.post(continuar(segunda), vinculo(UUID.randomUUID().toString(), true, versao(gui, segunda)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ANTERIOR_INVALIDA"));
        gui.post(continuar(segunda), vinculo(segunda, true, versao(gui, segunda)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ANTERIOR_INVALIDA"));
        // Versão lida diferente.
        gui.post(continuar(segunda), vinculo(primeira, true, versao(gui, segunda) + 5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));

        // Gerente vincula: a segunda comanda entra na visita da primeira e o couvert não se repete.
        gui.post(continuar(segunda), vinculo(primeira, true, versao(gui, segunda))).andExpect(status().isOk())
                .andExpect(jsonPath("$.continuesTab.id").value(primeira))
                .andExpect(jsonPath("$.visitId").value(visita(primeira).toString()));
        gabi.get(conta(segunda)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO_EM_OUTRA"))
                .andExpect(jsonPath("$.couvertDetail.chargedOnTab.id").value(primeira))
                .andExpect(jsonPath("$.couvertDetail.history[0].endKind").value("NAO_REPETIDO"));
        gabi.get(conta(primeira)).andExpect(jsonPath("$.couvert").value("15.00"));
        gabi.get(conta(outraAna)).andExpect(jsonPath("$.couvert").value("15.00"));
        assertThat(auditoria(UUID.fromString(segunda), "visita.%")).containsExactly("visita.vinculada");

        // O vínculo é único: nem de novo, nem a primeira (que já tem outra na visita) para outra visita.
        gui.post(continuar(segunda), vinculo(outraAna, true, versao(gui, segunda)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_JA_VINCULADA"));
        String terceira = comanda(gabi, "Bia");
        gui.post(continuar(terceira), vinculo(primeira, true, versao(gui, terceira))).andExpect(status().isOk());
        gabi.get(conta(terceira)).andExpect(jsonPath("$.couvert").value("0.00"));

        // Com a delegação, o garçom também vincula.
        delegar(vinculoGarcom, "VINCULAR_CONTINUACAO_VISITA");
        String quarta = comanda(gabi, "Ana");
        gabi.post(continuar(quarta), vinculo(outraAna, true, versao(gabi, quarta))).andExpect(status().isOk());
        gabi.get(conta(quarta)).andExpect(jsonPath("$.couvert").value("0.00"));
    }

    @Test
    void viradaDoDiaNaoEncerraESaidaValidadaEncerraAVisita() throws Exception {
        couvertAutomatico("12.00");
        // As duas comandas ficam em dias operacionais diferentes (pelo calendário da unidade), 23 h uma da outra.
        while (dia(relogio.instant()).equals(dia(relogio.instant().plus(Duration.ofHours(23))))) {
            relogio.advance(Duration.ofMinutes(30));
        }
        Browser gui = login.inUnit(gerente, loja);
        String noite = comanda(gui, "Caio");
        relogio.advance(Duration.ofHours(23));
        gui = login.inUnit(gerente, loja);
        String madrugada = comanda(gui, "Caio");
        assertThat(dia(aberta(madrugada))).isNotEqualTo(dia(aberta(noite)));
        gui.post(continuar(madrugada), vinculo(noite, true, versao(gui, madrugada))).andExpect(status().isOk());
        gui.get(conta(madrugada)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO_EM_OUTRA"));

        // Fora do prazo de continuação (24 h) não aparece nem vincula.
        relogio.advance(Duration.ofHours(2));
        gui = login.inUnit(gerente, loja);
        String tarde = comanda(gui, "Caio");
        gui.get(candidatas(tarde)).andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(noite))));
        gui.post(continuar(tarde), vinculo(noite, true, versao(gui, tarde)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMANDA_ANTERIOR_INVALIDA"));

        // A saída validada (contrato da H32) encerra a visita: a próxima entrada é visita nova, com couvert.
        String saiu = comanda(gui, "Duda");
        assertThat(tenants.inEstablishmentAs(loja, gerente,
                () -> comandas.endVisitByValidatedExitInCurrentUnit(UUID.fromString(saiu)))).isTrue();
        assertThat(tenants.inEstablishmentAs(loja, gerente,
                () -> comandas.endVisitByValidatedExitInCurrentUnit(UUID.fromString(saiu)))).isFalse();
        String voltou = comanda(gui, "Duda");
        gui.get(candidatas(voltou)).andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(saiu))));
        gui.post(continuar(voltou), vinculo(saiu, true, versao(gui, voltou)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITA_ENCERRADA_NA_SAIDA"));
        gui.get(conta(voltou)).andExpect(jsonPath("$.couvert").value("12.00"));
    }

    @Test
    void couvertManualCapturaOValorNaInclusaoESemRepetirNaVisita() throws Exception {
        novaConfiguracao("couvert_enabled = true, couvert_mode = 'MANUAL', couvert_amount = 12.00");
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String primeira = comanda(gabi, "Eva");
        gabi.get(conta(primeira)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.mode").value("MANUAL"))
                .andExpect(jsonPath("$.couvertDetail.state").value("SEM_COUVERT"))
                .andExpect(jsonPath("$.couvertDetail.canInclude").value(true))
                .andExpect(jsonPath("$.couvertDetail.includeAmount").value("12.00"))
                .andExpect(jsonPath("$.couvertDetail.canCancel").value(false));

        // O valor é o vigente na inclusão (mudou para 18,00 depois da abertura).
        novaConfiguracao("couvert_amount = 18.00");
        gabi.post(conta(primeira) + "/couvert", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.couvert").value("18.00"))
                .andExpect(jsonPath("$.couvertDetail.origin").value("MANUAL"))
                .andExpect(jsonPath("$.couvertDetail.canInclude").value(false));
        gabi.post(conta(primeira) + "/couvert", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUVERT_JA_INCLUIDO"));
        // Mudar a configuração depois não altera o couvert lançado.
        novaConfiguracao("couvert_amount = 25.00");
        gabi.get(conta(primeira)).andExpect(jsonPath("$.couvert").value("18.00"));

        // Segunda comanda da mesma visita: não inclui de novo.
        String segunda = comanda(gabi, "Eva");
        gui.post(continuar(segunda), vinculo(primeira, true, versao(gui, segunda))).andExpect(status().isOk());
        gabi.get(conta(segunda)).andExpect(jsonPath("$.couvertDetail.canInclude").value(false));
        gabi.post(conta(segunda) + "/couvert", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUVERT_JA_COBRADO_NA_VISITA"));

        // Comanda aberta no modo automático (ou sem couvert) não aceita inclusão manual.
        novaConfiguracao("couvert_mode = 'AUTOMATICO'");
        String automatica = comanda(gabi, "Fabi");
        gabi.post(conta(automatica) + "/couvert", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUVERT_NAO_MANUAL"));
        // Comanda manual aberta antes, com o couvert desligado agora: sem valor para incluir.
        novaConfiguracao("couvert_mode = 'MANUAL'");
        String manual = comanda(gabi, "Gil");
        novaConfiguracao("couvert_enabled = false");
        gabi.get(conta(manual)).andExpect(jsonPath("$.couvertDetail.canInclude").value(false));
        gabi.post(conta(manual) + "/couvert", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUVERT_DESABILITADO"));
    }

    @Test
    void cancelarPorIsencaoDispensaAVisitaInteira() throws Exception {
        couvertAutomatico("15.00");
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String primeira = comanda(gabi, "Hugo");
        String cobranca = cobrancaDe(gabi, primeira);

        // Garçom sem "cancelar item" não cancela; sem motivo válido recusa.
        gabi.get(conta(primeira)).andExpect(jsonPath("$.couvertDetail.canCancel").value(false));
        gabi.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "ISENCAO", "Aniversariante do dia"))
                .andExpect(status().isForbidden());
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "OUTRO", "Aniversariante do dia"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reasonKind"));
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "ISENCAO", "ok"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(UUID.randomUUID().toString(), "ISENCAO",
                "Aniversariante do dia")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));

        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "ISENCAO", "Aniversariante do dia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("DISPENSADO"))
                .andExpect(jsonPath("$.couvertDetail.history[0].endKind").value("CANCELADO"))
                .andExpect(jsonPath("$.couvertDetail.history[0].reasonKind").value("ISENCAO"))
                .andExpect(jsonPath("$.couvertDetail.history[0].reason").value("Aniversariante do dia"))
                .andExpect(jsonPath("$.couvertDetail.history[0].endedBy.name").value("Gui Gerente"));
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "ISENCAO", "Aniversariante do dia"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEM_COUVERT"));
        assertThat(auditoria(UUID.fromString(cobranca), "couvert.%")).containsExactly("couvert.cancelado");

        // A segunda comanda da visita não cobra: o couvert automático dela sai no vínculo.
        String segunda = comanda(gabi, "Hugo");
        gui.post(continuar(segunda), vinculo(primeira, true, versao(gui, segunda))).andExpect(status().isOk());
        gabi.get(conta(segunda)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("DISPENSADO"));

        // Cliente vê a dispensa e não precisa contestar.
        Browser cliente = vincularCliente(gabi, segunda);
        cliente.get("/api/v1/guest/tabs/" + segunda + "/bill").andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertState").value("DISPENSADO"));
    }

    @Test
    void duplicidadePreservaOValidoECorrecaoExigeInclusaoExplicita() throws Exception {
        couvertAutomatico("15.00");
        Browser gui = login.inUnit(gerente, loja);
        String primeira = comanda(gui, "Iris");
        String cobranca = cobrancaDe(gui, primeira);

        // Sem outro couvert válido na visita não é duplicidade.
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "DUPLICIDADE", "Cobrado duas vezes"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUVERT_SEM_DUPLICIDADE"));

        // Duas cobranças válidas na mesma visita (como a de uma comanda já paga antes do vínculo, H30): a segunda é
        // montada direto no banco, porque o vínculo hoje nunca deixa duas.
        String segunda = comanda(gui, "Iris");
        admin().update("UPDATE fastpay.tab SET visit_id = ? WHERE id = ?", visita(primeira), UUID.fromString(segunda));
        String duplicada = cobrancaDe(gui, segunda);
        gui.post(conta(segunda) + "/couvert/cancel", cancelamento(duplicada, "DUPLICIDADE", "Cobrado duas vezes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO_EM_OUTRA"));
        gui.get(conta(primeira)).andExpect(jsonPath("$.couvert").value("15.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO"));

        // Correção: nada volta sozinho; o valor certo é incluído explicitamente, com motivo.
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobranca, "CORRECAO", "Valor era o da semana"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("AGUARDANDO_CORRECAO"))
                .andExpect(jsonPath("$.couvertDetail.canCorrect").value(true));
        String terceira = comanda(gui, "Iris");
        gui.post(continuar(terceira), vinculo(primeira, true, versao(gui, terceira))).andExpect(status().isOk());
        gui.get(conta(terceira)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("AGUARDANDO_CORRECAO"));

        gui.post(conta(primeira) + "/couvert/correction", correcao("0.00", "Valor da semana"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount"));
        gui.post(conta(primeira) + "/couvert/correction", correcao("200.01", "Valor da semana"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount"));
        gui.post(conta(primeira) + "/couvert/correction", correcao("10.005", "Valor da semana"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount"));
        Browser gabi = login.inUnit(garcom, loja);
        gabi.post(conta(primeira) + "/couvert/correction", correcao("10.00", "Valor da semana"))
                .andExpect(status().isForbidden());
        gui.post(conta(primeira) + "/couvert/correction", correcao("10.00", "Valor da semana"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.couvert").value("10.00"))
                .andExpect(jsonPath("$.couvertDetail.origin").value("CORRECAO"))
                .andExpect(jsonPath("$.couvertDetail.state").value("COBRADO"));
        gui.post(conta(terceira) + "/couvert/correction", correcao("10.00", "Valor da semana"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUVERT_JA_COBRADO_NA_VISITA"));
        gui.get(conta(terceira)).andExpect(jsonPath("$.couvertDetail.state").value("COBRADO_EM_OUTRA"));
    }

    @Test
    void devolucaoPeloContratoDaH35SegueOMotivo() throws Exception {
        couvertAutomatico("15.00");
        Browser gui = login.inUnit(gerente, loja);
        String indevida = comanda(gui, "Juca");
        UUID cobranca = UUID.fromString(cobrancaDe(gui, indevida));

        // Motivo incompatível recusa sem mudar nada.
        assertThat(devolver(indevida, cobranca, Reason.DUPLICIDADE)).contains(Bills.Refusal.COUVERT_SEM_DUPLICIDADE);
        // Indevido: devolvido e a visita fica dispensada; a continuação não cobra.
        assertThat(devolver(indevida, cobranca, Reason.INDEVIDO)).isEmpty();
        assertThat(devolver(indevida, cobranca, Reason.INDEVIDO)).contains(Bills.Refusal.SEM_COUVERT);
        String continuacao = comanda(gui, "Juca");
        gui.post(continuar(continuacao), vinculo(indevida, true, versao(gui, continuacao))).andExpect(status().isOk());
        gui.get(conta(continuacao)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("DISPENSADO"));
        assertThat(auditoria(cobranca, "couvert.%")).containsExactly("couvert.devolvido");

        // Correção: devolvido sem recobrança automática; a visita aguarda a inclusão explícita.
        String corrigida = comanda(gui, "Lia");
        UUID outra = UUID.fromString(cobrancaDe(gui, corrigida));
        assertThat(devolver(corrigida, outra, Reason.CORRECAO)).isEmpty();
        gui.get(conta(corrigida)).andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertDetail.state").value("AGUARDANDO_CORRECAO"));
    }

    @Test
    void clienteVeOCouvertDaVisitaEContestaPeloProblemaDaConta() throws Exception {
        couvertAutomatico("15.00");
        Browser gabi = login.inUnit(garcom, loja);
        Browser gui = login.inUnit(gerente, loja);
        String primeira = comanda(gabi, "Mia");
        String segunda = comanda(gabi, "Mia");
        gui.post(continuar(segunda), vinculo(primeira, true, versao(gui, segunda))).andExpect(status().isOk());

        Browser cliente = vincularCliente(gabi, primeira);
        cliente.get("/api/v1/guest/tabs/" + primeira + "/bill").andExpect(jsonPath("$.couvert").value("15.00"))
                .andExpect(jsonPath("$.couvertState").value("COBRADO"))
                .andExpect(jsonPath("$.total").value("15.00"));
        Browser outro = vincularCliente(gabi, segunda);
        outro.get("/api/v1/guest/tabs/" + segunda + "/bill").andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertState").value("COBRADO_EM_OUTRA"));

        // Contestação: problema da conta marcado, que bloqueia a cobrança até ser tratado.
        cliente.post("/api/v1/guest/tabs/" + primeira + "/issues", "{\"orderLineId\":\"" + UUID.randomUUID()
                + "\",\"aboutCouvert\":true,\"description\":\"Não teve música\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("orderLineId"));
        cliente.post("/api/v1/guest/tabs/" + primeira + "/issues",
                "{\"aboutCouvert\":true,\"description\":\"Não teve música ao vivo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aboutCouvert").value(true))
                .andExpect(jsonPath("$.item").value(nullValue()));
        assertThat(tenants.inEstablishment(loja,
                () -> problemas.blocksChargeInCurrentUnit(UUID.fromString(primeira)))).isTrue();
        gui.get("/api/v1/attendance/calls").andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].aboutCouvert").value(true));

        // Quem pode cancela o couvert; cancelar não conclui o problema.
        gui.post(conta(primeira) + "/couvert/cancel", cancelamento(cobrancaDe(gui, primeira), "INDEVIDO",
                "Sem música ao vivo hoje")).andExpect(status().isOk());
        assertThat(tenants.inEstablishment(loja,
                () -> problemas.blocksChargeInCurrentUnit(UUID.fromString(primeira)))).isTrue();
        cliente.get("/api/v1/guest/tabs/" + primeira + "/bill").andExpect(jsonPath("$.couvert").value("0.00"))
                .andExpect(jsonPath("$.couvertState").value("DISPENSADO"));
    }

    @Test
    void doisVinculosSimultaneosDeixamUmCouvertNaVisita() throws Exception {
        novaConfiguracao("couvert_enabled = true, couvert_mode = 'MANUAL', couvert_amount = 12.00");
        Browser gui = login.inUnit(gerente, loja);
        // A primeira (manual, sem couvert incluído) deixa a visita sem couvert; as duas novas são automáticas.
        String primeira = comanda(gui, "Nina");
        novaConfiguracao("couvert_mode = 'AUTOMATICO'");
        String b = comanda(gui, "Nina");
        String c = comanda(gui, "Nina");
        long vb = versao(gui, b);
        long vc = versao(gui, c);
        Browser outroGerente = login.inUnit(gerente, loja);

        List<Integer> respostas = emParalelo(List.of(
                () -> gui.post(continuar(b), vinculo(primeira, true, vb)).andReturn().getResponse().getStatus(),
                () -> outroGerente.post(continuar(c), vinculo(primeira, true, vc)).andReturn().getResponse()
                        .getStatus()));
        assertThat(respostas).containsOnly(200);
        Integer validos = admin().queryForObject("""
                SELECT count(*)::int FROM fastpay.couvert_charge k JOIN fastpay.tab t ON t.id = k.tab_id
                 WHERE t.visit_id = ? AND k.ended_at IS NULL
                """, Integer.class, visita(primeira));
        assertThat(validos).isEqualTo(1);
    }

    // Apoio

    private Object dia(java.time.Instant instante) {
        return tenants.inEstablishment(loja, () -> configuracao.dayInCurrentUnit(instante));
    }

    private java.time.Instant aberta(String comanda) {
        return admin().queryForObject("SELECT opened_at FROM fastpay.tab WHERE id = ?", java.sql.Timestamp.class,
                UUID.fromString(comanda)).toInstant();
    }

    private Optional<Bills.Refusal> devolver(String comanda, UUID cobranca, Reason motivo) {
        return tenants.inEstablishmentAs(loja, gerente, () -> contas.recordCouvertRefundInCurrentUnit(
                UUID.fromString(comanda), cobranca, motivo, gerente, "teste-h35"));
    }

    private String cobrancaDe(Browser quem, String comanda) throws Exception {
        return JsonPath.read(quem.get(conta(comanda)).andReturn().getResponse().getContentAsString(),
                "$.couvertDetail.chargeId");
    }

    private UUID visita(String comanda) {
        return admin().queryForObject("SELECT visit_id FROM fastpay.tab WHERE id = ?", UUID.class,
                UUID.fromString(comanda));
    }

    private long versao(Browser quem, String comanda) throws Exception {
        Number v = JsonPath.read(quem.get("/api/v1/tabs/" + comanda).andReturn().getResponse().getContentAsString(),
                "$.version");
        return v.longValue();
    }

    private static String conta(String comanda) {
        return "/api/v1/tabs/" + comanda + "/bill";
    }

    private static String continuar(String comanda) {
        return "/api/v1/tabs/" + comanda + "/visit-continuation";
    }

    private static String candidatas(String comanda) {
        return "/api/v1/tabs/" + comanda + "/visit-continuation/candidates";
    }

    private static String vinculo(String anterior, boolean confirmado, long versao) {
        return "{\"previousTabId\":\"" + anterior + "\",\"stayConfirmed\":" + confirmado + ",\"version\":" + versao
                + "}";
    }

    private static String cancelamento(String cobranca, String motivo, String texto) {
        return "{\"chargeId\":\"" + cobranca + "\",\"reasonKind\":\"" + motivo + "\",\"reason\":\"" + texto + "\"}";
    }

    private static String correcao(String valor, String texto) {
        return "{\"amount\":\"" + valor + "\",\"reason\":\"" + texto + "\"}";
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

    private void couvertAutomatico(String valor) {
        novaConfiguracao("couvert_enabled = true, couvert_mode = 'AUTOMATICO', couvert_amount = " + valor);
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

    private Browser vincularCliente(Browser equipe, String comanda) throws Exception {
        String link = JsonPath.read(equipe.post("/api/v1/tabs/" + comanda + "/binding-qr", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.link");
        Browser cliente = new Browser(mvc);
        cliente.post("/api/v1/guest/bindings/confirm", "{\"token\":\"" + link.substring(link.indexOf('#') + 1)
                + "\"}").andExpect(status().isOk());
        return cliente;
    }

    private String comanda(Browser quem, String nome) throws Exception {
        relogio.advance(Duration.ofSeconds(1));
        return JsonPath.read(quem.post("/api/v1/tabs", "{\"name\":\"" + nome + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
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
