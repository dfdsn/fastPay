package tech.malyah.fastpay.tabs.web;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.ConsumerSession;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.tabs.ConsumerHistory;
import tech.malyah.fastpay.tabs.ConsumerHistory.AlreadyLinked;
import tech.malyah.fastpay.tabs.ConsumerHistory.Linked;
import tech.malyah.fastpay.tabs.ConsumerHistory.LinkedToAnotherAccount;
import tech.malyah.fastpay.tabs.ConsumerHistory.LinkState;
import tech.malyah.fastpay.tabs.ConsumerHistory.NoAccess;
import tech.malyah.fastpay.tabs.GuestCookie;

/**
 * Comanda no histórico do consumidor com Google (H27). Guardar a comanda exige as duas coisas no mesmo navegador: o
 * acesso avulso válido à comanda (cookie {@code FASTPAY_ACESSO}, por isso o caminho fica em {@code /api/v1/guest}) e a
 * conta Google conectada, mais a confirmação explícita. Login Google sozinho não dá acesso a comanda nenhuma.
 */
@RestController
class ConsumerHistoryController {

    private static final String NOT_FOUND_DETAIL =
            "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda.";

    private final ConsumerHistory history;
    private final ConsumerSession consumer;
    private final SessionContext staff;
    private final GuestCookie cookie;

    ConsumerHistoryController(ConsumerHistory history, ConsumerSession consumer, SessionContext staff,
            GuestCookie cookie) {
        this.history = history;
        this.consumer = consumer;
        this.staff = staff;
        this.cookie = cookie;
    }

    @GetMapping("/api/v1/guest/tabs/{id}/account-link")
    LinkState state(@PathVariable UUID id, HttpServletRequest request) {
        return history.state(cookie.tokens(request), id, consumer.account(request))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA", NOT_FOUND_DETAIL));
    }

    @PostMapping("/api/v1/guest/tabs/{id}/account-link")
    LinkState link(@PathVariable UUID id, @RequestBody LinkRequest body, HttpServletRequest request) {
        UUID account = consumer.requireAccount(request);
        if (body == null || !Boolean.TRUE.equals(body.confirmed())) {
            throw error(HttpStatus.BAD_REQUEST, "CONFIRMACAO_NECESSARIA",
                    "Confirme que esta comanda é sua para guardá-la no seu histórico.");
        }
        return switch (history.link(cookie.tokens(request), id, account)) {
            case Linked linked -> new LinkState(true, true);
            case AlreadyLinked already -> new LinkState(true, true);
            case LinkedToAnotherAccount other -> throw error(HttpStatus.CONFLICT, "COMANDA_DE_OUTRA_CONTA",
                    "Esta comanda já está no histórico de outra conta Google. Ela continua disponível neste aparelho.");
            case NoAccess none -> throw error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA", NOT_FOUND_DETAIL);
        };
    }

    @GetMapping("/api/v1/consumer/history")
    ConsumerHistory.HistoryPage history(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size, HttpServletRequest request) {
        return history.history(consumer.requireAccount(request), page, size);
    }

    /** Marca para a equipe: a comanda tem cliente cadastrado com Google (sem e-mail nem dados da conta). */
    @GetMapping("/api/v1/tabs/{id}/consumer-account")
    RegisteredView registered(@PathVariable UUID id, HttpServletRequest request) {
        return history.registered(staff.requireIdentity(request), staff.requireSelectedUnit(request), id)
                .map(RegisteredView::new)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA", "Comanda não encontrada."));
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    record LinkRequest(Boolean confirmed) {
    }

    record RegisteredView(boolean registered) {
    }
}
