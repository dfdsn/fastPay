package tech.malyah.fastpay.attendance.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import tech.malyah.fastpay.attendance.Attendance;
import tech.malyah.fastpay.attendance.Attendance.Done;
import tech.malyah.fastpay.attendance.Attendance.Invalid;
import tech.malyah.fastpay.attendance.Attendance.Outcome;
import tech.malyah.fastpay.attendance.Attendance.Refused;

/** Respostas do atendimento (H20, H21): recusas viram problemas HTTP com código e texto em português. */
final class Responses {

    private Responses() {
    }

    static <T> T respond(Outcome<T> outcome) {
        return switch (outcome) {
            case Done<T> done -> done.value();
            case Invalid<T> invalid -> {
                ErrorResponseException e = error(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", invalid.message());
                e.getBody().setProperty("field", invalid.field());
                throw e;
            }
            case Refused<T> refused -> throw refusal(refused);
        };
    }

    private static ErrorResponseException refusal(Refused<?> refused) {
        Attendance.Refusal refusal = refused.refusal();
        return switch (refusal) {
            case CHAMADO_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Chamado não encontrado nesta unidade. Atualize a tela.");
            case CHAMADO_CONCLUIDO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este chamado já foi concluído. Atualize a tela.");
            case CHAMADO_COM_RESPONSAVEL -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este chamado é do garçom responsável pela comanda. Para passar a outra pessoa, troque o "
                            + "responsável da comanda.");
            case CHAMADO_JA_ASSUMIDO -> withHolder(refusal, refused.holder(), "Outra pessoa já assumiu este chamado.",
                    "%s já assumiu este chamado.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Isto mudou desde que a tela foi carregada. Confira e tente de novo.");
            case PROBLEMA_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Problema não encontrado nesta unidade. Atualize a tela.");
            case PROBLEMA_TRATADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este problema já foi tratado. Atualize a tela.");
            case PROBLEMA_JA_ASSUMIDO -> withHolder(refusal, refused.holder(),
                    "Outra pessoa já está atendendo este problema.", "%s já está atendendo este problema.");
            case PROBLEMA_COM_RESPONSAVEL -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este problema é do garçom responsável pela comanda. Para passar a outra pessoa, troque o "
                            + "responsável da comanda.");
            case PROBLEMA_NAO_ATENDIDO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Comece o atendimento do problema antes de encaminhar ou concluir.");
            case PROBLEMA_EXIGE_GERENTE -> error(HttpStatus.FORBIDDEN, refusal.name(),
                    "Este problema chegou depois do encerramento da conta: só o gerente conclui, porque pode envolver "
                            + "devolução.");
            case PROBLEMA_JA_ABERTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Você já informou um problema sobre isso. A equipe está vendo; acompanhe aqui.");
            case ITEM_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Este item não está na sua comanda. Atualize a tela.");
        };
    }

    private static ErrorResponseException withHolder(Attendance.Refusal refusal, String holder, String anonymous,
            String named) {
        ErrorResponseException e = error(HttpStatus.CONFLICT, refusal.name(),
                holder == null ? anonymous : named.formatted(holder));
        e.getBody().setProperty("holder", holder);
        return e;
    }

    static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
