package tech.malyah.fastpay.tabs.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tech.malyah.fastpay.sync.Change;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Poll;
import tech.malyah.fastpay.sync.Streams;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.GuestAccess;
import tech.malyah.fastpay.tabs.GuestCookie;
import tech.malyah.fastpay.tabs.GuestAccess.GuestTab;
import tech.malyah.fastpay.tabs.GuestAccess.Remembered;
import tech.malyah.fastpay.tabs.domain.AccessTokens;
import tech.malyah.fastpay.tabs.infrastructure.TabsProperties;

/**
 * Consumidor avulso, sem login (H15, FR-08–FR-11). O token do QR vem no corpo (o link o leva no fragmento, que não
 * chega ao servidor nem aos logs). Confirmado o vínculo, o acesso fica num cookie persistente só deste caminho
 * ({@code HttpOnly}, {@code SameSite=Lax}, {@code Secure} fora do desenvolvimento local), que sobrevive a fechar o
 * navegador; a validade é sempre conferida no servidor. QR inválido por qualquer motivo: 410 {@code QR_INVALIDO};
 * comanda sem acesso válido neste navegador: 404 {@code COMANDA_NAO_ENCONTRADA}, sem dizer se ela existe.
 */
@RestController
@RequestMapping("/api/v1/guest")
class GuestController {

    static final String COOKIE = GuestCookie.NAME;
    private static final String PATH = "/api/v1/guest";

    /** O consumidor recebe só avisos da própria comanda, e nada da produção interna (especificação §11). */
    private static final Set<Topic> GUEST_TOPICS = EnumSet.of(Topic.COMANDA, Topic.PEDIDO, Topic.PROBLEMA, Topic.CONTA);

    private final GuestAccess access;
    private final TabsProperties properties;
    private final boolean secureCookie;
    private final Streams streams;
    private final Changes changes;
    private final GuestCookie cookie;

    GuestController(GuestAccess access, TabsProperties properties,
            @Value("${fastpay.auth.session-cookie-secure:true}") boolean secureCookie, Streams streams,
            Changes changes, GuestCookie cookie) {
        this.cookie = cookie;
        this.streams = streams;
        this.changes = changes;
        this.access = access;
        this.properties = properties;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/bindings/preview")
    GuestAccess.Preview preview(@RequestBody TokenRequest body) {
        return access.preview(body.token()).orElseThrow(GuestController::invalidQr);
    }

    @PostMapping("/bindings/confirm")
    GuestTab confirm(@RequestBody TokenRequest body, HttpServletRequest request, HttpServletResponse response) {
        GuestAccess.Bound bound = access.confirm(body.token()).orElseThrow(GuestController::invalidQr);
        List<String> tokens = new ArrayList<>();
        tokens.add(bound.grantToken());
        tokens.addAll(stored(request));
        remember(response, tokens);
        return bound.tab();
    }

    /** Comandas com acesso válido neste navegador; acessos que não valem mais saem do cookie. */
    @GetMapping("/tabs")
    List<GuestTab> tabs(HttpServletRequest request, HttpServletResponse response) {
        List<String> stored = stored(request);
        List<Remembered> valid = access.remembered(stored);
        if (valid.size() != stored.size()) {
            remember(response, valid.stream().map(Remembered::grantToken).toList());
        }
        return valid.stream().map(Remembered::tab).toList();
    }

    @GetMapping("/tabs/{id}")
    GuestTab tab(@PathVariable UUID id, HttpServletRequest request) {
        return access.remembered(stored(request)).stream().map(Remembered::tab)
                .filter(t -> t.tabId().equals(id)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "COMANDA_NAO_ENCONTRADA",
                        "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda."));
    }

    /**
     * Avisos da comanda em tempo real (H17): SSE só com o acesso válido guardado neste navegador, filtrado por esta
     * comanda e pelos tópicos do consumidor, e reconferido periodicamente (acesso revogado ou vencido encerra com
     * {@code ACESSO_ENCERRADO}).
     */
    @GetMapping("/tabs/{id}/stream")
    SseEmitter stream(@PathVariable UUID id, @RequestParam(required = false) String after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletRequest request,
            HttpServletResponse response) {
        GuestAccess.GuestStream target = guestStream(request, id);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String token = target.grantToken();
        return streams.open(new Streams.Subscription(target.establishmentId(), "consumidor:" + AccessTokens.hash(token),
                after != null ? after : lastEventId, change -> visibleToGuest(change, id),
                () -> access.valid(token).filter(t -> t.tabId().equals(id)).isPresent()
                        ? Optional.empty() : Optional.of("ACESSO_ENCERRADO")));
    }

    /** O mesmo por polling, quando o SSE não conecta. */
    @GetMapping("/tabs/{id}/changes")
    Poll changes(@PathVariable UUID id, @RequestParam(required = false) String after, HttpServletRequest request) {
        GuestAccess.GuestStream target = guestStream(request, id);
        return Poll.of(changes.read(target.establishmentId(), after, change -> visibleToGuest(change, id)));
    }

    private GuestAccess.GuestStream guestStream(HttpServletRequest request, UUID tabId) {
        return access.stream(stored(request), tabId).orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                "COMANDA_NAO_ENCONTRADA",
                "Não encontramos esta comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda."));
    }

    private static boolean visibleToGuest(Change change, UUID tabId) {
        return GUEST_TOPICS.contains(change.topic()) && tabId.equals(change.tabId());
    }

    private List<String> stored(HttpServletRequest request) {
        return cookie.tokens(request);
    }

    private void remember(HttpServletResponse response, List<String> tokens) {
        List<String> kept = new LinkedHashSet<>(tokens).stream().limit(properties.guestCookieMaxTabs()).toList();
        ResponseCookie cookie = ResponseCookie.from(COOKIE, String.join(".", kept))
                .path(PATH).httpOnly(true).secure(secureCookie).sameSite("Lax")
                .maxAge(kept.isEmpty() ? java.time.Duration.ZERO : properties.guestCookieMaxAge())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static ErrorResponseException invalidQr() {
        return error(HttpStatus.GONE, "QR_INVALIDO",
                "Este QR não vale mais (ele dura 2 minutos e só pode ser usado uma vez). Peça ao atendente um QR novo.");
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    record TokenRequest(String token) {
    }
}
