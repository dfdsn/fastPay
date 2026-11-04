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

    public enum Status {
        AGUARDANDO_PREPARO, EM_PREPARO, PRONTO, ENTREGUE
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
        IMPOSSIVEL_RETIRADO
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
        if (status == Status.ENTREGUE || limitMinutes == null) {
            return false;
        }
        return Duration.between(sentAt, now).compareTo(Duration.ofMinutes(limitMinutes)) > 0;
    }

    private static boolean beforeReady(Status status) {
        return status == Status.AGUARDANDO_PREPARO || status == Status.EM_PREPARO;
    }
}
