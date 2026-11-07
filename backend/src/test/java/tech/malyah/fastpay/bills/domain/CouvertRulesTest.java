package tech.malyah.fastpay.bills.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tech.malyah.fastpay.bills.domain.CouvertRules.Charge;
import tech.malyah.fastpay.bills.domain.CouvertRules.EndKind;
import tech.malyah.fastpay.bills.domain.CouvertRules.Reason;
import tech.malyah.fastpay.bills.domain.CouvertRules.VisitState;

/** Situação do couvert na visita e entradas da correção (H26, FR-12, FR-29, D-12). */
class CouvertRulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static Charge valido(UUID comanda) {
        return new Charge(UUID.randomUUID(), comanda, new BigDecimal("15.00"), null, null);
    }

    private static Charge encerrado(UUID comanda, EndKind tipo, Reason motivo) {
        return new Charge(UUID.randomUUID(), comanda, new BigDecimal("15.00"), tipo, motivo);
    }

    @Test
    void situacaoDaVisitaPelaOrdemDePrecedencia() {
        assertThat(CouvertRules.state(List.of())).isEqualTo(VisitState.SEM_COUVERT);
        // Não repetido no vínculo não muda nada sozinho.
        assertThat(CouvertRules.state(List.of(encerrado(B, EndKind.NAO_REPETIDO, null))))
                .isEqualTo(VisitState.SEM_COUVERT);
        assertThat(CouvertRules.state(List.of(valido(A)))).isEqualTo(VisitState.COBRADO);
        // Duplicidade devolvida com o original válido: continua cobrado.
        assertThat(CouvertRules.state(List.of(valido(A), encerrado(B, EndKind.DEVOLVIDO, Reason.DUPLICIDADE))))
                .isEqualTo(VisitState.COBRADO);
        assertThat(CouvertRules.state(List.of(encerrado(A, EndKind.CANCELADO, Reason.ISENCAO))))
                .isEqualTo(VisitState.DISPENSADO);
        assertThat(CouvertRules.state(List.of(encerrado(A, EndKind.DEVOLVIDO, Reason.INDEVIDO),
                encerrado(B, EndKind.CANCELADO, Reason.CORRECAO)))).isEqualTo(VisitState.DISPENSADO);
        assertThat(CouvertRules.state(List.of(encerrado(A, EndKind.CANCELADO, Reason.CORRECAO))))
                .isEqualTo(VisitState.AGUARDANDO_CORRECAO);
        // A inclusão de correção volta a cobrar.
        assertThat(CouvertRules.state(List.of(encerrado(A, EndKind.CANCELADO, Reason.CORRECAO), valido(A))))
                .isEqualTo(VisitState.COBRADO);
    }

    @Test
    void duplicidadeSoComOutroValidoEmOutraComanda() {
        Charge original = valido(A);
        Charge duplicado = valido(B);
        assertThat(CouvertRules.hasDuplicate(List.of(original), original)).isFalse();
        assertThat(CouvertRules.hasDuplicate(List.of(original, duplicado), duplicado)).isTrue();
        assertThat(CouvertRules.hasDuplicate(List.of(original, encerrado(B, EndKind.CANCELADO, Reason.INDEVIDO)),
                original)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({ "0.01, 0.01", "10, 10.00", "10.5, 10.50", "200.00, 200.00", " 15.00 , 15.00" })
    void valorDaCorrecaoAceito(String digitado, String esperado) {
        assertThat(CouvertRules.amount(digitado)).contains(new BigDecimal(esperado));
    }

    @ParameterizedTest
    @CsvSource({ "0.00", "200.01", "10.005", "-1", "1e2", "'10,00'", "abc", "1000" })
    void valorDaCorrecaoRecusado(String digitado) {
        assertThat(CouvertRules.amount(digitado)).isEmpty();
    }

    @Test
    void motivoSoDaLista() {
        assertThat(CouvertRules.reason("ISENCAO")).contains(Reason.ISENCAO);
        assertThat(CouvertRules.reason("isencao")).isEmpty();
        assertThat(CouvertRules.reason(null)).isEmpty();
        assertThat(CouvertRules.amount(null)).isEmpty();
        assertThat(Reason.INDEVIDO.waivesVisit()).isTrue();
        assertThat(Reason.DUPLICIDADE.waivesVisit()).isFalse();
    }
}
