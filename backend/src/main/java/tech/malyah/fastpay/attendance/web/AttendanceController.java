package tech.malyah.fastpay.attendance.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.attendance.Attendance;
import tech.malyah.fastpay.attendance.Attendance.Done;
import tech.malyah.fastpay.attendance.Attendance.Invalid;
import tech.malyah.fastpay.attendance.Attendance.Outcome;
import tech.malyah.fastpay.attendance.Attendance.Refused;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Fila de atendimento da unidade escolhida na sessão (H20). Ver e assumir: {@code OPERAR_COMANDA}; reatribuir e ver
 * para quem: {@code REATRIBUIR_ATENDIMENTO}. Conferidas no servidor a cada chamada. Entregar continua em
 * {@code POST /api/v1/production/lines/{id}/deliver}, que conclui o aviso.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceController {

    private final Attendance attendance;
    private final SessionContext session;

    AttendanceController(Attendance attendance, SessionContext session) {
        this.attendance = attendance;
        this.session = session;
    }

    @GetMapping("/calls")
    Attendance.Queue calls(HttpServletRequest request) {
        return attendance.queue(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    @PostMapping("/calls/{id}/claim")
    Attendance.Call claim(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(attendance.claim(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PostMapping("/calls/{id}/assign")
    Attendance.Call assign(@PathVariable UUID id, @RequestBody AssignRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(attendance.reassign(actor, session.requireSelectedUnit(request), id, body.version(),
                body.assigneeId(), correlationId()));
    }

    @GetMapping("/attendants")
    List<IdentitySummary> attendants(HttpServletRequest request) {
        return attendance.attendants(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    private static <T> T respond(Outcome<T> outcome) {
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
            case CHAMADO_JA_ASSUMIDO -> {
                ErrorResponseException e = error(HttpStatus.CONFLICT, refusal.name(), refused.holder() == null
                        ? "Outra pessoa já assumiu este chamado."
                        : "%s já assumiu este chamado.".formatted(refused.holder()));
                e.getBody().setProperty("holder", refused.holder());
                yield e;
            }
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este chamado mudou desde que a tela foi carregada. Confira e tente de novo.");
        };
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    record AssignRequest(UUID assigneeId, Long version) {
    }
}
