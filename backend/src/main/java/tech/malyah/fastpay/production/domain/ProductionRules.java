package tech.malyah.fastpay.production.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Regras puras das etapas de uma linha de produção (H18, FR-19, FR-21, FR-22, D-06). Uma linha enviada tem uma
 * sequência só, para toda a quantidade: aguardando preparo → em preparo → pronto → entregue, sem entrega parcial. Sem
 * setor, a linha nasce pronta e só pode ser entregue. "Não é possível atender" só antes do pronto, com motivo, e
 * impede qualquer avanço até ser retirado (ou a linha cancelada, H23).
 */
public final class ProductionRules {

    /** Motivo da impossibilidade (FR-21). */
    public static final int REASON_MIN = 5;
    public static final int REASON_MAX = 300;

    /** Linhas por lote de produção (H19, FR-69, D-13): parâmetro técnico global, não cota da loja. */
    public static final int BATCH_MAX = 50;

    public enum Status {
        AGUARDANDO_PREPARO, EM_PREPARO, PRONTO, ENTREGUE,
        /** Todas as unidades canceladas antes da entrega (H23, FR-20): a linha terminou. */
        CANCELADA
    }

    public enum Action {
        /** Produção começa o preparo. */
        INICIADO,
        /** Produção conclui o preparo. */
        PRONTO,
        /** Quem atende entrega a linha inteira a todos os destinos. */
        ENTREGUE,
        /** Produção sinaliza que não é possível atender, com motivo. */
        IMPOSSIVEL,
        /** Produção retira a sinalização (por exemplo, o insumo chegou). */
        IMPOSSIVEL_RETIRADO,
        /** Quem tem a permissão cancela unidades da linha (H23); não é etapa da produção, ver {@link #cancel}. */
        CANCELADO
    }

    /** Situação lida da linha travada. */
    public record Line(Status status, boolean hasSector, boolean impossible) {
    }

    public enum Refusal {
        /** A ação não cabe na etapa atual (já avançou, ou ainda não chegou lá). */
        ETAPA_INVALIDA,
        /** A linha está sinalizada como impossível: nada avança até retirar a sinalização ou cancelar. */
        LINHA_IMPOSSIVEL
    }

    /** Resultado: a etapa seguinte, ou a recusa. */
    public sealed interface Decision {
    }

    public record Next(Status status, boolean impossible) implements Decision {
    }

    public record Refused(Refusal refusal) implements Decision {
    }

    private ProductionRules() {
    }

    /** Etapa inicial no recebimento (FR-19): sem setor, pronta. */
    public static Status initial(boolean hasSector) {
        return hasSector ? Status.AGUARDANDO_PREPARO : Status.PRONTO;
    }

    public static Decision decide(Line line, Action action) {
        boolean advance = action == Action.INICIADO || action == Action.PRONTO || action == Action.ENTREGUE;
        if (advance && line.impossible()) {
            return new Refused(Refusal.LINHA_IMPOSSIVEL);
        }
        return switch (action) {
            case INICIADO -> line.status() == Status.AGUARDANDO_PREPARO
                    ? new Next(Status.EM_PREPARO, false) : new Refused(Refusal.ETAPA_INVALIDA);
            case PRONTO -> line.status() == Status.EM_PREPARO
                    ? new Next(Status.PRONTO, false) : new Refused(Refusal.ETAPA_INVALIDA);
            case ENTREGUE -> line.status() == Status.PRONTO
                    ? new Next(Status.ENTREGUE, false) : new Refused(Refusal.ETAPA_INVALIDA);
            case IMPOSSIVEL -> line.hasSector() && !line.impossible() && beforeReady(line.status())
                    ? new Next(line.status(), true) : new Refused(Refusal.ETAPA_INVALIDA);
            case IMPOSSIVEL_RETIRADO -> line.impossible()
                    ? new Next(line.status(), false) : new Refused(Refusal.ETAPA_INVALIDA);
            case CANCELADO -> new Refused(Refusal.ETAPA_INVALIDA);
        };
    }

    /**
     * Motivo normalizado (espaços nas pontas e repetidos), ou {@code null} se vazio, curto ou longo demais.
     */
    public static String reason(String raw) {
        if (raw == null) {
            return null;
        }
        String r = raw.strip().replaceAll("\\s+", " ");
        return r.length() < REASON_MIN || r.length() > REASON_MAX ? null : r;
    }

    /**
     * Atraso (FR-22): a linha ainda não entregue passou do limite do setor desde o envio. Só destaque visual; sem
     * limite (linha sem setor) nunca atrasa.
     */
    public static boolean late(Status status, Instant sentAt, Integer limitMinutes, Instant now) {
        if (status == Status.ENTREGUE || status == Status.CANCELADA || limitMinutes == null) {
            return false;
        }
        return Duration.between(sentAt, now).compareTo(Duration.ofMinutes(limitMinutes)) > 0;
    }

    /**
     * Efeito de cancelar unidades na linha (H23, FR-20, D-06). {@code to}: a etapa depois do cancelamento (a mesma, ou
     * {@link Status#CANCELADA} quando não sobra nada e a linha ainda não foi entregue; entregue continua entregue, com
     * o histórico). {@code requiresAck}: a produção precisa dar ciência (em preparo ou pronto, com setor); aguardando
     * preparo a fila só é ajustada. {@code clearsImpossible}: o cancelamento total resolve a sinalização.
     */
    public record CancelPlan(Status to, int cancelledAfter, boolean requiresAck, boolean clearsImpossible) {
    }

    /**
     * @param quantity unidades originais da linha
     * @param cancelledBefore unidades já canceladas (todas as comandas)
     * @param cancelNow unidades a cancelar agora, já conferidas contra as ativas na comanda
     */
    public static CancelPlan cancel(Line line, int quantity, int cancelledBefore, int cancelNow) {
        if (cancelNow < 1 || cancelledBefore < 0 || cancelledBefore + cancelNow > quantity
                || line.status() == Status.CANCELADA) {
            throw new IllegalArgumentException("Cancelamento acima do que resta na linha.");
        }
        int after = cancelledBefore + cancelNow;
        boolean total = after == quantity;
        Status to = total && line.status() != Status.ENTREGUE ? Status.CANCELADA : line.status();
        boolean ack = line.hasSector() && (line.status() == Status.EM_PREPARO || line.status() == Status.PRONTO);
        return new CancelPlan(to, after, ack, total && line.impossible());
    }

    private static boolean beforeReady(Status status) {
        return status == Status.AGUARDANDO_PREPARO || status == Status.EM_PREPARO;
    }
}
