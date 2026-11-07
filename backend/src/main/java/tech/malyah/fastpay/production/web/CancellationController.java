package tech.malyah.fastpay.production.web;

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

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.production.Cancellations;
import tech.malyah.fastpay.production.Cancellations.Done;
import tech.malyah.fastpay.production.Cancellations.Invalid;
import tech.malyah.fastpay.production.Cancellations.Outcome;
import tech.malyah.fastpay.production.Cancellations.Refused;

/**
 * Cancelamento de quantidade (H23): {@code CANCELAR_ITEM} na comanda; ciência da produção: {@code OPERAR_PRODUCAO}.
 * Conferidas no servidor a cada chamada.
 */
@RestController
class CancellationController {

    private final Cancellations cancellations;
    private final SessionContext session;

    CancellationController(Cancellations cancellations, SessionContext session) {
        this.cancellations = cancellations;
        this.session = session;
    }

    @PostMapping("/api/v1/tabs/{tabId}/items/{orderLineId}/cancel")
    Cancellations.Cancelled cancel(@PathVariable UUID tabId, @PathVariable UUID orderLineId,
            @RequestBody CancelRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(cancellations.cancel(actor, session.requireSelectedUnit(request), tabId, orderLineId,
                body.quantity(), body.expectedQuantity(), body.reason(), UUID.randomUUID().toString()));
    }

    @GetMapping("/api/v1/production/cancellations")
    Cancellations.Notices pending(@RequestParam(required = false) UUID sectorId, HttpServletRequest request) {
        return cancellations.pending(session.requireIdentity(request), session.requireSelectedUnit(request),
                sectorId);
    }

    @PostMapping("/api/v1/production/cancellations/{id}/acknowledge")
    Cancellations.Acknowledged acknowledge(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(cancellations.acknowledge(actor, session.requireSelectedUnit(request), id,
                UUID.randomUUID().toString()));
    }

    private static <T> T respond(Outcome<T> outcome) {
        return switch (outcome) {
            case Done<T> done -> done.value();
            case Invalid<T> invalid -> {
                ErrorResponseException e = error(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", invalid.message());
                e.getBody().setProperty("field", invalid.field());
                throw e;
            }
            case Refused<T> refused -> throw refusal(refused.refusal());
        };
    }

    private static ErrorResponseException refusal(Cancellations.Refusal refusal) {
        return switch (refusal) {
            case COMANDA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda não encontrada nesta unidade.");
            case COMANDA_ENCERRADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda já foi encerrada: o item não pode mais ser cancelado. Se houve pagamento, o gerente "
                            + "registra a devolução.");
            case COMANDA_EM_FECHAMENTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda está em fechamento: o item não pode ser cancelado agora. Para mudar, reabra a comanda com o motivo.");
            case ITEM_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Item não encontrado nesta comanda. Atualize a tela.");
            case ITEM_AGUARDANDO_PRODUCAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O item ainda está sendo encaminhado à produção. Aguarde alguns segundos e tente de novo; se o "
                            + "encaminhamento falhou, reprocesse o pedido antes.");
            case ITEM_JA_CANCELADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Todas as unidades deste item já foram canceladas nesta comanda.");
            case QUANTIDADE_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A quantidade deste item mudou desde que a tela foi carregada. Confira e tente de novo.");
            case CANCELAMENTO_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Cancelamento não encontrado nesta unidade. Atualize a tela.");
            case CIENCIA_NAO_EXIGIDA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este cancelamento não precisa de ciência da produção.");
        };
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    record CancelRequest(Integer quantity, Integer expectedQuantity, String reason) {
    }
}
