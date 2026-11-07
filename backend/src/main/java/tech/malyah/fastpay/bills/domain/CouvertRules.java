package tech.malyah.fastpay.bills.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Couvert na visita (H26, FR-12, FR-29, FR-71, D-12, CA-13; T-03 da H26). Sem estado: recebe os couverts lançados nas
 * comandas da visita e diz a situação dela.
 *
 * <ul>
 * <li>Uma cobrança válida por comanda e, com a visita travada, uma por visita: o couvert não se repete.</li>
 * <li>Cancelado ou devolvido por <b>indevido</b> ou <b>isenção</b>: a visita fica dispensada.</li>
 * <li>Por <b>duplicidade</b>: só quando há outro válido na visita, que continua cobrado.</li>
 * <li>Por <b>correção</b>: nada volta sozinho; a visita aguarda a inclusão explícita do valor correto.</li>
 * </ul>
 */
public final class CouvertRules {

    /** R$ 0,01 a R$ 200,00, o mesmo intervalo do couvert na configuração (H14); mais de 2 casas é recusado. */
    public static final BigDecimal MIN = new BigDecimal("0.01");
    public static final BigDecimal MAX = new BigDecimal("200.00");
    private static final Pattern MONEY = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

    private CouvertRules() {
    }

    public enum Origin {
        /** Da versão da configuração capturada na abertura. */
        AUTOMATICO,
        /** Incluído pela equipe, com o valor da configuração vigente na inclusão. */
        MANUAL,
        /** Inclusão explícita do valor correto depois de um cancelamento ou devolução por correção. */
        CORRECAO
    }

    public enum EndKind {
        CANCELADO,
        /** Devolução (H35). */
        DEVOLVIDO,
        /** A comanda foi vinculada a uma visita que já tinha couvert cobrado, dispensado ou aguardando correção. */
        NAO_REPETIDO
    }

    /** Motivo de cancelar ou devolver o couvert (FR-12). */
    public enum Reason {
        INDEVIDO,
        ISENCAO,
        DUPLICIDADE,
        CORRECAO;

        /** Indevido e isenção dispensam o couvert na visita inteira. */
        public boolean waivesVisit() {
            return this == INDEVIDO || this == ISENCAO;
        }
    }

    /** Situação do couvert na visita. */
    public enum VisitState {
        /** Nenhum couvert válido nem dispensa: pode haver um (automático já lançado, ou manual a incluir). */
        SEM_COUVERT,
        /** Há couvert válido em alguma comanda da visita. */
        COBRADO,
        /** Dispensado na visita por indevido ou isenção. */
        DISPENSADO,
        /** Cancelado ou devolvido por correção: só a inclusão explícita do valor correto. */
        AGUARDANDO_CORRECAO
    }

    /** Couvert lançado numa comanda da visita; {@code endKind} nulo = válido. */
    public record Charge(UUID id, UUID tabId, BigDecimal amount, EndKind endKind, Reason endReason) {

        public boolean valid() {
            return endKind == null;
        }
    }

    public static VisitState state(List<Charge> charges) {
        if (charges.stream().anyMatch(Charge::valid)) {
            return VisitState.COBRADO;
        }
        if (charges.stream().anyMatch(c -> c.endReason() != null && c.endReason().waivesVisit())) {
            return VisitState.DISPENSADO;
        }
        if (charges.stream().anyMatch(c -> c.endReason() == Reason.CORRECAO)) {
            return VisitState.AGUARDANDO_CORRECAO;
        }
        return VisitState.SEM_COUVERT;
    }

    /** Duplicidade só existe se há outro couvert válido na visita, em outra comanda. */
    public static boolean hasDuplicate(List<Charge> charges, Charge charge) {
        return charges.stream().anyMatch(c -> c.valid() && !c.id().equals(charge.id())
                && !c.tabId().equals(charge.tabId()));
    }

    public static Optional<Reason> reason(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Reason.valueOf(raw.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Valor digitado da correção: texto decimal, de R$ 0,01 a R$ 200,00, até 2 casas. */
    public static Optional<BigDecimal> amount(String raw) {
        if (raw == null || !MONEY.matcher(raw.strip()).matches()) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(raw.strip()).setScale(2);
        return value.compareTo(MIN) >= 0 && value.compareTo(MAX) <= 0 ? Optional.of(value) : Optional.empty();
    }
}
