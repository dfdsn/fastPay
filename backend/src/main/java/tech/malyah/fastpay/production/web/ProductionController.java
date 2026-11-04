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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.production.Production;
import tech.malyah.fastpay.production.Production.Done;
import tech.malyah.fastpay.production.Production.Invalid;
import tech.malyah.fastpay.production.Production.Outcome;
import tech.malyah.fastpay.production.Production.Refused;

/**
 * Fila de produção e entrega da unidade escolhida na sessão (H18). Fila e etapas de preparo: {@code OPERAR_PRODUCAO};
 * prontos e entrega: {@code OPERAR_COMANDA}. Conferidas no servidor a cada chamada.
 */
@RestController
@RequestMapping("/api/v1/production")
class ProductionController {

    private final Production production;
    private final SessionContext session;

    ProductionController(Production production, SessionContext session) {
        this.production = production;
        this.session = session;
    }

    @GetMapping("/queue")
    Production.Board queue(@RequestParam(required = false) UUID sectorId, HttpServletRequest request) {
        return production.queue(session.requireIdentity(request), session.requireSelectedUnit(request), sectorId);
    }

    @GetMapping("/deliveries")
    Production.Board deliveries(HttpServletRequest request) {
        return production.deliveries(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    @PostMapping("/lines/{id}/start")
    Production.Line start(@PathVariable UUID id, @RequestBody VersionRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(production.start(actor, session.requireSelectedUnit(request), id, body.version(),
                correlationId()));
    }

    @PostMapping("/lines/{id}/ready")
    Production.Line ready(@PathVariable UUID id, @RequestBody VersionRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(production.ready(actor, session.requireSelectedUnit(request), id, body.version(),
                correlationId()));
    }

    @PostMapping("/lines/{id}/impossible")
    Production.Line impossible(@PathVariable UUID id, @RequestBody ImpossibleRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(production.impossible(actor, session.requireSelectedUnit(request), id, body.version(),
                body.reason(), correlationId()));
    }

    @PostMapping("/lines/{id}/impossible/withdraw")
    Production.Line withdraw(@PathVariable UUID id, @RequestBody VersionRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(production.withdrawImpossible(actor, session.requireSelectedUnit(request), id,
                body.version(), correlationId()));
    }

    @PostMapping("/lines/{id}/deliver")
    Production.Line deliver(@PathVariable UUID id, @RequestBody VersionRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(production.deliver(actor, session.requireSelectedUnit(request), id, body.version(),
                correlationId()));
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

    private static ErrorResponseException refusal(Production.Refusal refusal) {
        return switch (refusal) {
            case LINHA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Item não encontrado nesta unidade. Atualize a tela.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item mudou desde que a tela foi carregada. Confira os dados atualizados e tente de novo.");
            case ETAPA_INVALIDA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item não está na etapa certa para isso. Atualize a tela.");
            case LINHA_IMPOSSIVEL -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este item está marcado como \"Não é possível atender\". Retire a marcação ou peça o "
                            + "cancelamento antes de continuar.");
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

    record VersionRequest(Long version) {
    }

    record ImpossibleRequest(Long version, String reason) {
    }
}
