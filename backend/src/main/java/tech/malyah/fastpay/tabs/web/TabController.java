package tech.malyah.fastpay.tabs.web;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.Done;
import tech.malyah.fastpay.tabs.Tabs.Invalid;
import tech.malyah.fastpay.tabs.Tabs.Outcome;
import tech.malyah.fastpay.tabs.Tabs.Refused;

/**
 * Comandas da unidade escolhida na sessão (H15). Abrir, listar, trocar mesa e mostrar o QR: {@code OPERAR_COMANDA}.
 * Recuperar o acesso do consumidor: {@code RECUPERAR_ACESSO_CONSUMIDOR}, com a conferência presencial de P-07.
 * Reatribuir o garçom responsável (H20): {@code REATRIBUIR_ATENDIMENTO}. Continuação de visita (H26):
 * {@code VINCULAR_CONTINUACAO_VISITA}.
 * A permissão é conferida no servidor a cada chamada (403 {@code PERMISSAO_NEGADA}).
 */
@RestController
@RequestMapping("/api/v1/tabs")
class TabController {

    private final Tabs tabs;
    private final SessionContext session;

    TabController(Tabs tabs, SessionContext session) {
        this.tabs = tabs;
        this.session = session;
    }

    @PostMapping
    Tabs.Tab open(@RequestBody OpenRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(tabs.open(actor, session.requireSelectedUnit(request), body.name(), body.table(),
                correlationId()));
    }

    @GetMapping
    List<Tabs.Tab> active(HttpServletRequest request) {
        return tabs.active(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    @GetMapping("/{id}")
    Tabs.Tab find(@PathVariable UUID id, HttpServletRequest request) {
        return respond(tabs.find(session.requireIdentity(request), session.requireSelectedUnit(request), id));
    }

    @PutMapping("/{id}/table")
    Tabs.Tab changeTable(@PathVariable UUID id, @RequestBody TableRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        if (body.version() == null) {
            throw error(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Informe a versão lida da comanda.");
        }
        return respond(tabs.changeTable(actor, session.requireSelectedUnit(request), id, body.version(), body.table(),
                correlationId()));
    }

    @PostMapping("/{id}/binding-qr")
    Tabs.BindingQr bindingQr(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(tabs.bindingQr(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PostMapping("/{id}/access-recovery")
    Tabs.BindingQr recover(@PathVariable UUID id, @RequestBody RecoveryRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(tabs.recover(actor, session.requireSelectedUnit(request), id, body.nameChecked(),
                body.accountCheck(), body.reason(), correlationId()));
    }

    /** Reatribui o garçom responsável (H20): {@code REATRIBUIR_ATENDIMENTO}, só na modalidade garçom responsável. */
    @PutMapping("/{id}/responsible")
    Tabs.Tab reassign(@PathVariable UUID id, @RequestBody ResponsibleRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(tabs.reassign(actor, session.requireSelectedUnit(request), id, body.version(),
                body.responsibleId(), correlationId()));
    }

    /** Comandas que podem ser a anterior da continuação de visita (H26), por hora de abertura. */
    @GetMapping("/{id}/visit-continuation/candidates")
    List<Tabs.ContinuationCandidate> continuationCandidates(@PathVariable UUID id, HttpServletRequest request) {
        return respond(tabs.continuationCandidates(session.requireIdentity(request),
                session.requireSelectedUnit(request), id));
    }

    /** Vincula a comanda à visita da anterior (H26), com a permanência confirmada presencialmente. */
    @PostMapping("/{id}/visit-continuation")
    Tabs.Tab linkVisit(@PathVariable UUID id, @RequestBody ContinuationRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(tabs.linkVisit(actor, session.requireSelectedUnit(request), id, body.previousTabId(),
                body.stayConfirmed(), body.version(), correlationId()));
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

    private static ErrorResponseException refusal(Tabs.Refusal refusal) {
        return switch (refusal) {
            case NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda não encontrada entre as ativas desta unidade. Atualize a lista.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Outra pessoa alterou esta comanda. Atualize e confira antes de salvar.");
            case UNIDADE_NAO_ATIVA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta unidade ainda não foi ativada pela fastPay. Comandas só podem ser abertas depois da ativação.");
            case SEM_NUMERO_LIVRE -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Não há número livre para uma nova comanda. Encerre comandas antigas antes.");
            case CONSUMIDOR_JA_VINCULADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O cliente já tem acesso a esta comanda. Para trocar de aparelho, use Recuperar acesso.");
            case SEM_ACESSO_A_RECUPERAR -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Ninguém confirmou o acesso a esta comanda ainda. Basta mostrar o QR ao cliente.");
            case ATENDIMENTO_CENTRAL -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda é atendida pela central e não tem responsável. Reatribua cada chamado na tela de "
                            + "atendimento.");
            case COMANDA_JA_VINCULADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda já faz parte de uma visita com outra comanda. O vínculo é feito uma vez só.");
            case COMANDA_ANTERIOR_INVALIDA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Escolha uma comanda desta unidade aberta antes desta, nas últimas 24 horas. Atualize a lista.");
            case VISITA_ENCERRADA_NA_SAIDA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O cliente já teve a saída validada nessa comanda: esta entrada é uma visita nova.");
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

    record OpenRequest(String name, String table) {
    }

    record TableRequest(String table, Long version) {
    }

    record ResponsibleRequest(UUID responsibleId, Long version) {
    }

    record ContinuationRequest(UUID previousTabId, Boolean stayConfirmed, Long version) {
    }

    record RecoveryRequest(Boolean nameChecked, String accountCheck, String reason) {
    }
}
