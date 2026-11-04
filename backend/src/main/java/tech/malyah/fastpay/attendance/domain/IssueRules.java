package tech.malyah.fastpay.attendance.domain;

import java.util.Optional;
import java.util.UUID;

import tech.malyah.fastpay.establishments.AttendanceMode;

/**
 * Problema do consumidor (H21, FR-24, FR-25, CA-14). Regras puras: quem chama lê o problema travado na transação do
 * comando.
 * <ul>
 * <li>Estados: aberto, em atendimento, tratado; sem volta. Tratado não muda mais.</li>
 * <li>Atender: na central é exclusivo (como o chamado da H20); no garçom responsável, a primeira pessoa que atende
 * registra o início, e as demais ajudam.</li>
 * <li>Reatribuir: só na central, com a versão lida (no garçom responsável troca-se o responsável da comanda).</li>
 * <li>Encaminhar ao gerente e concluir: só depois de alguém atender. Concluir exige resolução e a declaração da correção;
 * o problema que chegou com a comanda já encerrada (paga) só é concluído por quem autoriza devolução.</li>
 * </ul>
 */
public final class IssueRules {

    /** Tamanho da descrição do cliente e da resolução da equipe. */
    public static final int MIN_TEXT = 5;
    public static final int MAX_TEXT = 300;

    private IssueRules() {
    }

    public enum Status {
        ABERTO,
        EM_ATENDIMENTO,
        TRATADO
    }

    /** O que a pessoa declara ao concluir: marcar tratado nunca altera valores (FR-24). */
    public enum Correction {
        /** Explicado, nada a corrigir. */
        SEM_CORRECAO,
        /** A correção (cancelamento, desconto, transferência) já foi feita pelo caminho próprio, antes da baixa. */
        CORRECAO_REALIZADA
    }

    /** Situação do problema como a regra precisa. */
    public record Issue(AttendanceMode mode, Status status, UUID attendedBy, boolean forwarded, boolean afterClosure,
            long version) {
    }

    public enum Refusal {
        TRATADO,
        /** Central: outra pessoa já está atendendo. */
        JA_ASSUMIDO,
        /** Garçom responsável: o problema é do responsável da comanda. */
        COM_RESPONSAVEL,
        /** Ninguém começou a atender ainda. */
        NAO_ATENDIDO,
        /** Chegou depois do encerramento: só quem autoriza devolução conclui. */
        EXIGE_GERENTE,
        VERSAO_DESATUALIZADA
    }

    public sealed interface Decision {
    }

    public record Refused(Refusal refusal) implements Decision {
    }

    public record Unchanged() implements Decision {
    }

    /** Começar (ou passar) o atendimento para esta pessoa. */
    public record Attend(UUID attendant) implements Decision {
    }

    public record Forward() implements Decision {
    }

    public record Conclude() implements Decision {
    }

    /** Texto do cliente ou da equipe, sem espaços nas pontas, de 5 a 300 caracteres; vazio se não serve. */
    public static Optional<String> text(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= MIN_TEXT && length <= MAX_TEXT ? Optional.of(trimmed) : Optional.empty();
    }

    /** Começar a atender. */
    public static Decision attend(Issue issue, UUID actorId) {
        if (issue.status() == Status.TRATADO) {
            return new Refused(Refusal.TRATADO);
        }
        if (actorId.equals(issue.attendedBy())) {
            return new Unchanged();
        }
        if (issue.mode() == AttendanceMode.CENTRAL) {
            return issue.attendedBy() == null ? new Attend(actorId) : new Refused(Refusal.JA_ASSUMIDO);
        }
        // Garçom responsável: quem chega depois ajuda; o início continua registrado.
        return issue.status() == Status.ABERTO ? new Attend(actorId) : new Unchanged();
    }

    /** Reatribuir (gerente), só na central. */
    public static Decision reassign(Issue issue, long version, UUID assigneeId) {
        if (issue.status() == Status.TRATADO) {
            return new Refused(Refusal.TRATADO);
        }
        if (issue.mode() != AttendanceMode.CENTRAL) {
            return new Refused(Refusal.COM_RESPONSAVEL);
        }
        if (issue.version() != version) {
            return new Refused(Refusal.VERSAO_DESATUALIZADA);
        }
        return assigneeId.equals(issue.attendedBy()) ? new Unchanged() : new Attend(assigneeId);
    }

    /** Encaminhar ao gerente: a correção exige autorização que quem atende não tem, ou ele não pode concluir. */
    public static Decision forward(Issue issue, long version) {
        Optional<Refusal> common = common(issue, version);
        if (common.isPresent()) {
            return new Refused(common.get());
        }
        return issue.forwarded() ? new Unchanged() : new Forward();
    }

    /** Concluir como tratado. {@code mayAuthorizeRefund}: quem conclui autoriza devolução (gerente). */
    public static Decision conclude(Issue issue, long version, boolean mayAuthorizeRefund) {
        Optional<Refusal> common = common(issue, version);
        if (common.isPresent()) {
            return new Refused(common.get());
        }
        if (issue.afterClosure() && !mayAuthorizeRefund) {
            return new Refused(Refusal.EXIGE_GERENTE);
        }
        return new Conclude();
    }

    private static Optional<Refusal> common(Issue issue, long version) {
        if (issue.status() == Status.TRATADO) {
            return Optional.of(Refusal.TRATADO);
        }
        if (issue.version() != version) {
            return Optional.of(Refusal.VERSAO_DESATUALIZADA);
        }
        if (issue.status() == Status.ABERTO) {
            return Optional.of(Refusal.NAO_ATENDIDO);
        }
        return Optional.empty();
    }
}
