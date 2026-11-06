package tech.malyah.fastpay.orders.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.orders.Orders.Done;
import tech.malyah.fastpay.orders.Orders.Invalid;
import tech.malyah.fastpay.orders.Orders.NeedsReview;
import tech.malyah.fastpay.orders.Orders.Outcome;
import tech.malyah.fastpay.orders.Orders.Refused;
import tech.malyah.fastpay.orders.domain.OrderRules.RawLine;

/**
 * Pedidos da unidade escolhida na sessão (H16). Enviar, consultar a operação, listar os pedidos da comanda e
 * reprocessar o encaminhamento: {@code OPERAR_COMANDA}, conferida no servidor a cada chamada.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final Orders orders;
    private final SessionContext session;

    OrderController(Orders orders, SessionContext session) {
        this.orders = orders;
        this.session = session;
    }

    @PostMapping
    Orders.Sent send(@RequestBody SendRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        List<RawLine> lines = body.lines() == null ? null
                : body.lines().stream().map(l -> l == null ? null
                        : new RawLine(l.productId(), l.quantity(), l.note(), l.expectedPrice())).toList();
        return respond(orders.send(actor, session.requireSelectedUnit(request), body.tabId(), body.operationKey(),
                lines, correlationId()));
    }

    @GetMapping("/operations/{operationKey}")
    Orders.SentOrder operation(@PathVariable UUID operationKey, HttpServletRequest request) {
        return respond(orders.operation(session.requireIdentity(request), session.requireSelectedUnit(request),
                operationKey));
    }

    @GetMapping
    List<Orders.SentOrder> ofTab(@RequestParam UUID tabId, HttpServletRequest request) {
        return respond(orders.ofTab(session.requireIdentity(request), session.requireSelectedUnit(request), tabId));
    }

    @GetMapping("/received")
    List<Orders.ReceivedLine> received(@RequestParam UUID tabId, HttpServletRequest request) {
        return respond(orders.receivedOfTab(session.requireIdentity(request), session.requireSelectedUnit(request),
                tabId));
    }

    @PostMapping("/{id}/forwarding/retry")
    Orders.SentOrder retry(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(orders.retryForwarding(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    private static <T> T respond(Outcome<T> outcome) {
        return switch (outcome) {
            case Done<T> done -> done.value();
            case Invalid<T> invalid -> {
                ErrorResponseException e = error(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", invalid.message());
                e.getBody().setProperty("field", invalid.field());
                throw e;
            }
            case NeedsReview<T> review -> {
                ErrorResponseException e = error(HttpStatus.CONFLICT, "PEDIDO_PRECISA_REVISAO", review.unavailable()
                        .isEmpty()
                                ? "Preço mudou desde que o pedido foi montado. Confira os novos valores e envie de novo."
                                : "Há item esgotado ou fora do catálogo. Nada foi enviado: ajuste o rascunho e envie de novo.");
                e.getBody().setProperty("unavailable", review.unavailable());
                e.getBody().setProperty("priceChanges", review.priceChanges().stream()
                        .map(c -> new PriceChangeView(c.productId(), c.seenPrice().toPlainString(),
                                c.currentPrice().toPlainString()))
                        .toList());
                throw e;
            }
            case Refused<T> refused -> throw refusal(refused.refusal());
        };
    }

    private static ErrorResponseException refusal(Orders.Refusal refusal) {
        return switch (refusal) {
            case COMANDA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda não encontrada nesta unidade. Atualize a lista.");
            case COMANDA_ENCERRADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda não recebe mais pedidos. O rascunho continua guardado neste aparelho.");
            case CHAVE_REUTILIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este envio já foi usado para outro pedido. Atualize a página e confira os pedidos da comanda.");
            case PEDIDO_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Pedido não encontrado. Se o envio não chegou, o rascunho continua guardado neste aparelho.");
            case ENCAMINHAMENTO_SEM_FALHA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O encaminhamento deste pedido não está com falha. Atualize a comanda.");
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

    record LineRequest(UUID productId, Integer quantity, String note, String expectedPrice) {
    }

    record SendRequest(UUID tabId, UUID operationKey, List<LineRequest> lines) {
    }

    record PriceChangeView(UUID productId, String seenPrice, String currentPrice) {
    }
}
