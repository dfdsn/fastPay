package tech.malyah.fastpay.tabs.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.tabs.GuestAccess;
import tech.malyah.fastpay.tabs.GuestAccess.GuestTab;
import tech.malyah.fastpay.tabs.GuestAccess.Remembered;
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

    static final String COOKIE = "FASTPAY_ACESSO";
    private static final String PATH = "/api/v1/guest";

    private final GuestAccess access;
    private final TabsProperties properties;
    private final boolean secureCookie;

    GuestController(GuestAccess access, TabsProperties properties,
            @Value("${fastpay.auth.session-cookie-secure:true}") boolean secureCookie) {
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

    private List<String> stored(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).findFirst()
                .map(c -> Arrays.stream(c.getValue().split("\\.")).filter(s -> !s.isBlank())
                        .limit(properties.guestCookieMaxTabs()).toList())
                .orElse(List.of());
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
