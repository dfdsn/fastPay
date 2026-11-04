package tech.malyah.fastpay.attendance.domain;

import java.util.Optional;
import java.util.UUID;

import tech.malyah.fastpay.establishments.AttendanceMode;

/**
 * Quem responde por um chamado (H20, FR-23). Regras puras: quem chama lê o chamado travado na transação do comando.
 * <ul>
 * <li>Garçom responsável: o dono é o responsável atual da comanda; ninguém assume o chamado, o gerente troca o
 * responsável da comanda.</li>
 * <li>Central: o chamado nasce sem dono; a primeira pessoa que assume fica com ele de forma exclusiva, e assumir de
 * novo o próprio chamado não muda nada. O gerente reatribui a qualquer pessoa que atende.</li>
 * </ul>
 * Concluído não muda mais. A versão lida protege a reatribuição de sobrescrever uma mudança que a tela não viu.
 */
public final class CallRules {

    private CallRules() {
    }

    /** Situação do chamado como a regra precisa. {@code claimedBy}: quem assumiu, na central. */
    public record Call(AttendanceMode mode, UUID claimedBy, boolean concluded, long version) {
    }

    public enum Refusal {
        /** Já foi concluído (entregue, sinalização retirada). */
        CONCLUIDO,
        /** Garçom responsável: o chamado é do responsável da comanda. */
        COM_RESPONSAVEL,
        /** Central: outra pessoa já assumiu. */
        JA_ASSUMIDO,
        /** Mudou desde a leitura. */
        VERSAO_DESATUALIZADA
    }

    /** Resultado: recusa, nada a fazer (já é de quem pediu) ou gravar o novo dono. */
    public sealed interface Decision {
    }

    public record Refused(Refusal refusal) implements Decision {
    }

    public record Unchanged() implements Decision {
    }

    public record Assign(UUID assignee) implements Decision {
    }

    /** Assumir um chamado da central (exclusivo): só se ainda não tem dono, ou se já é de quem pede. */
    public static Decision claim(Call call, UUID actorId) {
        Optional<Refusal> common = common(call);
        if (common.isPresent()) {
            return new Refused(common.get());
        }
        if (actorId.equals(call.claimedBy())) {
            return new Unchanged();
        }
        if (call.claimedBy() != null) {
            return new Refused(Refusal.JA_ASSUMIDO);
        }
        return new Assign(actorId);
    }

    /** Reatribuir um chamado da central (gerente), com a versão lida. */
    public static Decision reassign(Call call, long version, UUID assigneeId) {
        Optional<Refusal> common = common(call);
        if (common.isPresent()) {
            return new Refused(common.get());
        }
        if (call.version() != version) {
            return new Refused(Refusal.VERSAO_DESATUALIZADA);
        }
        if (assigneeId.equals(call.claimedBy())) {
            return new Unchanged();
        }
        return new Assign(assigneeId);
    }

    private static Optional<Refusal> common(Call call) {
        if (call.concluded()) {
            return Optional.of(Refusal.CONCLUIDO);
        }
        if (call.mode() != AttendanceMode.CENTRAL) {
            return Optional.of(Refusal.COM_RESPONSAVEL);
        }
        return Optional.empty();
    }
}
