package tech.malyah.fastpay.attendance.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.attendance.Attendance;
import tech.malyah.fastpay.attendance.ConsumerIssues;
import tech.malyah.fastpay.attendance.domain.IssueRules.Correction;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Fila de atendimento da unidade escolhida na sessão (H20). Ver e assumir: {@code OPERAR_COMANDA}; reatribuir e ver
 * para quem: {@code REATRIBUIR_ATENDIMENTO}. Conferidas no servidor a cada chamada. Entregar continua em
 * {@code POST /api/v1/production/lines/{id}/deliver}, que conclui o aviso. Problemas do consumidor (H21): atender e
 * encaminhar com {@code ATENDER_PROBLEMA}, reatribuir na central com {@code REATRIBUIR_ATENDIMENTO}, concluir com
 * {@code CONCLUIR_PROBLEMA}.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class AttendanceController {

    private final Attendance attendance;
    private final SessionContext session;
    private final ConsumerIssues issues;

    AttendanceController(Attendance attendance, SessionContext session, ConsumerIssues issues) {
        this.issues = issues;
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
        return Responses.respond(attendance.claim(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PostMapping("/calls/{id}/assign")
    Attendance.Call assign(@PathVariable UUID id, @RequestBody AssignRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(attendance.reassign(actor, session.requireSelectedUnit(request), id, body.version(),
                body.assigneeId(), correlationId()));
    }

    @GetMapping("/attendants")
    List<IdentitySummary> attendants(HttpServletRequest request) {
        return attendance.attendants(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    @PostMapping("/issues/{id}/attend")
    ConsumerIssues.Issue attend(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(issues.attend(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PostMapping("/issues/{id}/assign")
    ConsumerIssues.Issue assignIssue(@PathVariable UUID id, @RequestBody AssignRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(issues.reassign(actor, session.requireSelectedUnit(request), id, body.version(),
                body.assigneeId(), correlationId()));
    }

    @PostMapping("/issues/{id}/forward")
    ConsumerIssues.Issue forward(@PathVariable UUID id, @RequestBody VersionRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(issues.forward(actor, session.requireSelectedUnit(request), id, body.version(),
                correlationId()));
    }

    @PostMapping("/issues/{id}/conclude")
    ConsumerIssues.Issue conclude(@PathVariable UUID id, @RequestBody ConcludeRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(issues.conclude(actor, session.requireSelectedUnit(request), id, body.version(),
                body.resolution(), body.correction(), correlationId()));
    }

    /** Para quem o gerente pode passar um problema da central (H21). */
    @GetMapping("/issue-attendants")
    List<IdentitySummary> issueAttendants(HttpServletRequest request) {
        return issues.attendants(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    record AssignRequest(UUID assigneeId, Long version) {
    }

    record VersionRequest(Long version) {
    }

    record ConcludeRequest(Long version, String resolution, Correction correction) {
    }
}
