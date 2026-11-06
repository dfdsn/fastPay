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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.production.DeliveryCorrections;
import tech.malyah.fastpay.production.DeliveryCorrections.Done;
import tech.malyah.fastpay.production.DeliveryCorrections.Invalid;
import tech.malyah.fastpay.production.DeliveryCorrections.Outcome;
import tech.malyah.fastpay.production.DeliveryCorrections.Refused;
import tech.malyah.fastpay.production.Production;

/**
 * Correção de entrega (H24): entregues recentes, desfazer e ocorrência, todos com {@code DESFAZER_ENTREGA}, conferida
 * no servidor a cada chamada.
 */
@RestController
@RequestMapping("/api/v1/production")
class DeliveryCorrectionController {

    private final Production production;
    private final DeliveryCorrections corrections;
    private final SessionContext session;

    DeliveryCorrectionController(Production production, DeliveryCorrections corrections, SessionContext session) {
        this.production = production;
        this.corrections = corrections;
        this.session = session;
    }

    @GetMapping("/delivered")
    Production.Board delivered(HttpServletRequest request) {
        return production.delivered(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    @PostMapping("/lines/{id}/undo-delivery")
    Production.Line undo(@PathVariable UUID id, @RequestBody ReasonRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(corrections.undo(actor, session.requireSelectedUnit(request), id, body.version(),
                body.reason(), UUID.randomUUID().toString()));
    }

    @PostMapping("/lines/{id}/delivery-occurrence")
    DeliveryCorrections.Occurrence occurrence(@PathVariable UUID id, @RequestBody ReasonRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(corrections.occurrence(actor, session.requireSelectedUnit(request), id, body.version(),
                body.reason(), UUID.randomUUID().toString()));
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

    private static ErrorResponseException refusal(DeliveryCorrections.Refusal refusal) {
        return switch (refusal) {
            case LINHA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Item não encontrado nesta unidade. Atualize a tela.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item mudou desde que a tela foi carregada. Confira os dados atualizados e tente de novo.");
            case ITEM_NAO_ENTREGUE -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item não está entregue. Atualize a tela.");
            case ITEM_CANCELADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Todas as unidades deste item foram canceladas: não há entrega para desfazer.");
            case COMANDA_ENCERRADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Uma comanda deste item já foi encerrada: a entrega não pode ser desfeita. Registre uma "
                            + "ocorrência; devolução, se couber, é feita pelo gerente.");
            case ENTREGA_PODE_SER_DESFEITA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "As comandas deste item estão abertas: desfaça a entrega em vez de registrar ocorrência.");
        };
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    record ReasonRequest(Long version, String reason) {
    }
}
