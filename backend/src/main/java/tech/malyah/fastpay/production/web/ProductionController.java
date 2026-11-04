package tech.malyah.fastpay.production.web;

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
import tech.malyah.fastpay.production.Production;
import tech.malyah.fastpay.production.Production.Done;
import tech.malyah.fastpay.production.Production.Invalid;
import tech.malyah.fastpay.production.Production.Outcome;
import tech.malyah.fastpay.production.Production.Refused;

/**
 * Fila de produção e entrega da unidade escolhida na sessão (H18). Fila e etapas de preparo: {@code OPERAR_PRODUCAO};
 * prontos e entrega: {@code OPERAR_COMANDA}. Conferidas no servidor a cada chamada. Lote da produção (H19):
 * {@code OPERAR_PRODUCAO}, conferida de novo em cada linha.
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

    /**
     * Lote de produção (H19): até 50 linhas, cada uma com a versão lida, todas com a mesma ação. 200 com o resultado
     * de cada linha na ordem do pedido, mesmo com falhas; 400 só para pedido inválido (vazio, mais de 50, linha
     * repetida, sem versão), 403 sem a permissão da produção.
     */
    @PostMapping("/batch")
    BatchResponse batch(@RequestBody BatchRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        List<Production.BatchItem> items = body.lines() == null ? null
                : body.lines().stream().map(l -> l == null ? null : new Production.BatchItem(l.id(), l.version()))
                        .toList();
        Production.BatchResult result = respond(production.batch(actor, session.requireSelectedUnit(request),
                body.action(), items, correlationId()));
        return new BatchResponse(result.concluded(), result.ignored(), result.failed(), result.lines().stream()
                .map(l -> new BatchLineResponse(l.lineId(), l.status(), l.code(), message(l))).toList());
    }

    private static String message(Production.BatchLine line) {
        if (line.code() == null) {
            return null;
        }
        return switch (line.code()) {
            case Production.JA_APLICADA -> "Já estava feito; nada mudou.";
            case Production.SEM_PERMISSAO -> "Você não tem mais permissão para operar a produção nesta unidade.";
            case Production.SEM_ACESSO -> "Você não tem mais acesso a esta unidade.";
            case Production.FALHA_INESPERADA -> "Não foi possível atualizar este item. Tente de novo.";
            case "DADOS_INVALIDOS" -> "Atualize a tela e tente de novo.";
            default -> refusal(Production.Refusal.valueOf(line.code())).getBody().getDetail();
        };
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

    record BatchRequest(Production.BatchAction action, List<BatchLineRequest> lines) {
    }

    record BatchLineRequest(UUID id, Long version) {
    }

    record BatchLineResponse(UUID lineId, Production.BatchStatus status, String code, String message) {
    }

    record BatchResponse(int concluded, int ignored, int failed, List<BatchLineResponse> lines) {
    }

    record ImpossibleRequest(Long version, String reason) {
    }
}
