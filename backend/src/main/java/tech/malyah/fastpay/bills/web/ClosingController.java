package tech.malyah.fastpay.bills.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.bills.Closings;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Conferência e fechamento da comanda pela equipe da unidade escolhida na sessão (H28): ver e iniciar com
 * {@code OPERAR_COMANDA}; reabrir com {@code REABRIR_COMANDA} e motivo. Conferido no servidor a cada chamada.
 */
@RestController
@RequestMapping("/api/v1/tabs/{tabId}/closing")
class ClosingController {

    private final Closings closings;
    private final SessionContext session;

    ClosingController(Closings closings, SessionContext session) {
        this.closings = closings;
        this.session = session;
    }

    @GetMapping
    Closings.ClosingView view(@PathVariable UUID tabId, HttpServletRequest request) {
        return Responses.respond(closings.view(session.requireIdentity(request), session.requireSelectedUnit(request),
                tabId));
    }

    @PostMapping
    Closings.ClosingView start(@PathVariable UUID tabId, @RequestBody StartRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(closings.start(actor, session.requireSelectedUnit(request), tabId,
                body.expectedTotal(), UUID.randomUUID().toString()));
    }

    @PostMapping("/reopen")
    Closings.ClosingView reopen(@PathVariable UUID tabId, @RequestBody ReopenRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(closings.reopen(actor, session.requireSelectedUnit(request), tabId,
                body.closingId(), body.reason(), UUID.randomUUID().toString()));
    }

    record StartRequest(String expectedTotal) {
    }

    record ReopenRequest(UUID closingId, String reason) {
    }
}
