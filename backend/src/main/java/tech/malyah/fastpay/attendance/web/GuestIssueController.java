package tech.malyah.fastpay.attendance.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.attendance.ConsumerIssues;
import tech.malyah.fastpay.tabs.GuestCookie;

/**
 * Problemas informados pelo consumidor avulso (H21, FR-24, FR-25), com o acesso da própria comanda guardado no
 * navegador (H15) e CSRF. Sem acesso válido para a comanda — de outra pessoa, revogado ou vencido — a resposta é a
 * mesma da H15: 404 {@code COMANDA_NAO_ENCONTRADA}, sem dizer se ela existe.
 */
@RestController
@RequestMapping("/api/v1/guest/tabs/{tabId}/issues")
class GuestIssueController {

    private final ConsumerIssues issues;
    private final GuestCookie cookie;

    GuestIssueController(ConsumerIssues issues, GuestCookie cookie) {
        this.issues = issues;
        this.cookie = cookie;
    }

    @GetMapping
    ConsumerIssues.GuestIssues list(@PathVariable UUID tabId, HttpServletRequest request) {
        return issues.ofGuestTab(cookie.tokens(request), tabId).orElseThrow(GuestIssueController::tabNotFound);
    }

    @PostMapping
    ConsumerIssues.GuestIssue report(@PathVariable UUID tabId, @RequestBody ReportRequest body,
            HttpServletRequest request) {
        return Responses.respond(issues.report(cookie.tokens(request), tabId, body.orderLineId(),
                Boolean.TRUE.equals(body.aboutCouvert()), body.description(),
                UUID.randomUUID().toString()).orElseThrow(GuestIssueController::tabNotFound));
    }

    private static ErrorResponseException tabNotFound() {
        return Responses.error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA",
                "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda.");
    }

    record ReportRequest(UUID orderLineId, Boolean aboutCouvert, String description) {
    }
}
