package tech.malyah.fastpay.bills.web;

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

import tech.malyah.fastpay.bills.Closings;
import tech.malyah.fastpay.tabs.GuestCookie;

/**
 * Conferência e fechamento pelo consumidor avulso (H28, UJ-01), com o acesso da própria comanda (H15) e CSRF. Sem
 * acesso válido para a comanda, 404 {@code COMANDA_NAO_ENCONTRADA}, como na H15. Reabrir é só da equipe.
 */
@RestController
@RequestMapping("/api/v1/guest/tabs/{tabId}/closing")
class GuestClosingController {

    private final Closings closings;
    private final GuestCookie cookie;

    GuestClosingController(Closings closings, GuestCookie cookie) {
        this.closings = closings;
        this.cookie = cookie;
    }

    @GetMapping
    Closings.GuestClosingView view(@PathVariable UUID tabId, HttpServletRequest request) {
        return closings.ofGuestTab(cookie.tokens(request), tabId).orElseThrow(GuestClosingController::tabNotFound);
    }

    @PostMapping
    Closings.GuestClosingView start(@PathVariable UUID tabId, @RequestBody StartRequest body,
            HttpServletRequest request) {
        return Responses.respond(closings.startAsGuest(cookie.tokens(request), tabId, body.expectedTotal(),
                UUID.randomUUID().toString()).orElseThrow(GuestClosingController::tabNotFound));
    }

    private static ErrorResponseException tabNotFound() {
        return Responses.error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA",
                "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda.");
    }

    record StartRequest(String expectedTotal) {
    }
}
