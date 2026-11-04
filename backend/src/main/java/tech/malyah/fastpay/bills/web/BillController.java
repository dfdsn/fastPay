package tech.malyah.fastpay.bills.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.bills.Bills;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Conta da comanda pela equipe da unidade escolhida na sessão (H22). Ver e retirar/devolver o serviço:
 * {@code OPERAR_COMANDA}; conceder, retirar e revisar desconto: {@code CONCEDER_DESCONTO}, dentro do limite da pessoa.
 * Tudo conferido no servidor a cada chamada; valores vão e voltam como texto decimal.
 */
@RestController
@RequestMapping("/api/v1/tabs/{tabId}/bill")
class BillController {

    private final Bills bills;
    private final SessionContext session;

    BillController(Bills bills, SessionContext session) {
        this.bills = bills;
        this.session = session;
    }

    @GetMapping
    Bills.Bill view(@PathVariable UUID tabId, HttpServletRequest request) {
        return Responses.respond(bills.view(session.requireIdentity(request), session.requireSelectedUnit(request),
                tabId));
    }

    @PutMapping("/discount")
    Bills.Bill grant(@PathVariable UUID tabId, @RequestBody GrantRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(bills.grant(actor, session.requireSelectedUnit(request), tabId,
                body.currentDiscountId(), body.kind(), body.value(), body.reason(), correlationId()));
    }

    @PostMapping("/discount/remove")
    Bills.Bill remove(@PathVariable UUID tabId, @RequestBody RemoveRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(bills.remove(actor, session.requireSelectedUnit(request), tabId, body.discountId(),
                body.reason(), correlationId()));
    }

    @PostMapping("/discount/review")
    Bills.Bill review(@PathVariable UUID tabId, @RequestBody ReviewRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(bills.review(actor, session.requireSelectedUnit(request), tabId, body.discountId(),
                correlationId()));
    }

    @PutMapping("/service")
    Bills.Bill service(@PathVariable UUID tabId, @RequestBody ServiceRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return Responses.respond(bills.chooseService(actor, session.requireSelectedUnit(request), tabId,
                body.waived(), correlationId()));
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    record GrantRequest(UUID currentDiscountId, String kind, String value, String reason) {
    }

    record RemoveRequest(UUID discountId, String reason) {
    }

    record ReviewRequest(UUID discountId) {
    }

    record ServiceRequest(Boolean waived) {
    }
}
