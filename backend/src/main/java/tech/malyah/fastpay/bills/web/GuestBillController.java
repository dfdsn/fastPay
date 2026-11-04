package tech.malyah.fastpay.bills.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.bills.Bills;
import tech.malyah.fastpay.tabs.GuestCookie;

/**
 * Conta do consumidor avulso (H22, FR-28), com o acesso da própria comanda guardado no navegador (H15) e CSRF: vê os
 * valores e retira ou devolve o serviço. Sem acesso válido para a comanda, a resposta é a mesma da H15: 404
 * {@code COMANDA_NAO_ENCONTRADA}, sem dizer se ela existe.
 */
@RestController
@RequestMapping("/api/v1/guest/tabs/{tabId}/bill")
class GuestBillController {

    private final Bills bills;
    private final GuestCookie cookie;

    GuestBillController(Bills bills, GuestCookie cookie) {
        this.bills = bills;
        this.cookie = cookie;
    }

    @GetMapping
    Bills.GuestBill view(@PathVariable UUID tabId, HttpServletRequest request) {
        return bills.ofGuestTab(cookie.tokens(request), tabId).orElseThrow(GuestBillController::tabNotFound);
    }

    @PutMapping("/service")
    Bills.GuestBill service(@PathVariable UUID tabId, @RequestBody ServiceRequest body, HttpServletRequest request) {
        return Responses.respond(bills.chooseServiceAsGuest(cookie.tokens(request), tabId, body.waived(),
                UUID.randomUUID().toString()).orElseThrow(GuestBillController::tabNotFound));
    }

    private static ErrorResponseException tabNotFound() {
        return Responses.error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA",
                "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda.");
    }

    record ServiceRequest(Boolean waived) {
    }
}
