package tech.malyah.fastpay.production.web;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.production.Transfers;
import tech.malyah.fastpay.production.Transfers.Done;
import tech.malyah.fastpay.production.Transfers.Invalid;
import tech.malyah.fastpay.production.Transfers.Outcome;
import tech.malyah.fastpay.production.Transfers.Refused;

/** Transferência de unidades entre comandas (H25): {@code TRANSFERIR_UNIDADES}, conferida no servidor a cada chamada. */
@RestController
class TransferController {

    private final Transfers transfers;
    private final SessionContext session;

    TransferController(Transfers transfers, SessionContext session) {
        this.transfers = transfers;
        this.session = session;
    }

    @PostMapping("/api/v1/tabs/{tabId}/items/{orderLineId}/transfer")
    Transfers.Transferred transfer(@PathVariable UUID tabId, @PathVariable UUID orderLineId,
            @RequestBody TransferRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(transfers.transfer(actor, session.requireSelectedUnit(request), tabId, orderLineId,
                body.toTabId(), body.quantity(), body.expectedQuantity(), body.reason(),
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

    private static ErrorResponseException refusal(Transfers.Refusal refusal) {
        return switch (refusal) {
            case COMANDA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda não encontrada nesta unidade.");
            case COMANDA_ENCERRADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda já foi encerrada: os itens dela não podem mais ser transferidos.");
            case DESTINO_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda de destino não encontrada nesta unidade. Atualize a lista de comandas.");
            case DESTINO_ENCERRADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda de destino já foi encerrada. Escolha uma comanda aberta.");
            case COMANDA_EM_FECHAMENTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda está em fechamento: os itens dela não podem ser transferidos agora. Para mudar, reabra a comanda com o motivo.");
            case DESTINO_EM_FECHAMENTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda de destino está em fechamento. Escolha outra comanda ou peça para reabri-la.");
            case ITEM_NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Item não encontrado nesta comanda. Atualize a tela.");
            case ITEM_AGUARDANDO_PRODUCAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O item ainda está sendo encaminhado à produção. Aguarde alguns segundos e tente de novo; se o "
                            + "encaminhamento falhou, reprocesse o pedido antes.");
            case ITEM_SEM_UNIDADES -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item não tem mais unidades ativas nesta comanda.");
            case QUANTIDADE_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A quantidade deste item mudou desde que a tela foi carregada. Confira e tente de novo.");
            case PROBLEMA_NO_ITEM -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Há um problema do consumidor em aberto sobre este item. Trate o problema antes de transferir.");
            case PROBLEMA_NA_CONTA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Há um problema do consumidor em aberto sobre a conta desta comanda. Trate o problema antes de "
                            + "transferir itens dela.");
            case PROBLEMA_NA_CONTA_DESTINO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Há um problema do consumidor em aberto sobre a conta da comanda de destino. Trate o problema "
                            + "antes de transferir itens para ela.");
        };
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    record TransferRequest(UUID toTabId, Integer quantity, Integer expectedQuantity, String reason) {
    }
}
