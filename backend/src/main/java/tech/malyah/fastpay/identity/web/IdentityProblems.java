package tech.malyah.fastpay.identity.web;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tech.malyah.fastpay.identity.AuthenticationRequiredException;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.UnitAccessDeniedException;
import tech.malyah.fastpay.identity.UnitNotSelectedException;

/**
 * Respostas {@code application/problem+json} de sessão e acesso à unidade, com código estável e
 * mensagem em português para a UI (especificação §6). Vale para qualquer controller que use
 * {@code SessionContext}/{@code Memberships.inUnit}.
 */
@RestControllerAdvice
class IdentityProblems {

    private final SessionContext session;

    IdentityProblems(SessionContext session) {
        this.session = session;
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    ProblemDetail authenticationRequired() {
        return problem(HttpStatus.UNAUTHORIZED, "SESSAO_AUSENTE", "Sua sessão não está ativa. Entre novamente.");
    }

    @ExceptionHandler(UnitNotSelectedException.class)
    ProblemDetail unitNotSelected() {
        return problem(HttpStatus.CONFLICT, "UNIDADE_NAO_SELECIONADA", "Escolha uma unidade para continuar.");
    }

    /**
     * Sem vínculo ativo: mesma resposta para unidade inexistente, alheia ou desativada. Se era a unidade
     * escolhida na sessão, a escolha é esquecida e a pessoa volta à seleção.
     */
    @ExceptionHandler(UnitAccessDeniedException.class)
    ProblemDetail unitAccessDenied(UnitAccessDeniedException denied, HttpServletRequest request) {
        if (denied.establishmentId().isPresent() && denied.establishmentId().equals(session.selectedUnit(request))) {
            session.clearUnit(request);
        }
        return problem(HttpStatus.FORBIDDEN, "UNIDADE_NAO_AUTORIZADA",
                "Você não tem acesso a esta unidade. Escolha outra unidade.");
    }

    static ErrorResponseException invalidRequest(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST,
                problem(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return problem;
    }
}
