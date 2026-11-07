package tech.malyah.fastpay.identity.web;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import tech.malyah.fastpay.identity.ConsumerSession;
import tech.malyah.fastpay.identity.application.GoogleSignIn;
import tech.malyah.fastpay.identity.application.GoogleSignIn.AccountCreated;
import tech.malyah.fastpay.identity.application.GoogleSignIn.Denied;
import tech.malyah.fastpay.identity.application.GoogleSignIn.Failed;
import tech.malyah.fastpay.identity.application.GoogleSignIn.NothingPending;
import tech.malyah.fastpay.identity.application.GoogleSignIn.OutdatedNotice;
import tech.malyah.fastpay.identity.application.GoogleSignIn.PrivacyNoticeRequired;
import tech.malyah.fastpay.identity.application.GoogleSignIn.RedirectToGoogle;
import tech.malyah.fastpay.identity.application.GoogleSignIn.SignedIn;
import tech.malyah.fastpay.identity.application.GoogleSignIn.Throttled;
import tech.malyah.fastpay.identity.application.GoogleSignIn.Unavailable;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Login Google opcional do consumidor (H27). {@code start} e {@code callback} são navegações do navegador (redirect
 * 302), não chamadas do Angular: o retorno é protegido pelo state de uso único guardado na sessão, não pelo CSRF. A
 * tela de destino recebe só {@code ?google=conectado|recusado|falhou|indisponivel|limite}. Aceitar ou recusar o aviso
 * de privacidade e sair são mutações com CSRF.
 */
@RestController
@RequestMapping("/api/v1/consumer")
class ConsumerController {

    static final String PRIVACY_PAGE = "/privacidade-google";

    private final GoogleSignIn signIn;
    private final ConsumerSession session;

    ConsumerController(GoogleSignIn signIn, ConsumerSession session) {
        this.signIn = signIn;
        this.session = session;
    }

    @GetMapping("/google/start")
    ResponseEntity<Void> start(@RequestParam(required = false) String returnTo, HttpServletRequest request) {
        return switch (signIn.start(request, returnTo)) {
            case RedirectToGoogle google -> redirect(google.uri());
            case Unavailable unavailable -> redirect(back(returnTo, "indisponivel"));
            case Throttled throttled -> redirect(back(returnTo, "limite"));
        };
    }

    @GetMapping("/google/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            HttpServletRequest request) {
        return switch (signIn.callback(request, state, code, error)) {
            case SignedIn ok -> redirect(back(ok.returnTo(), "conectado"));
            case PrivacyNoticeRequired notice -> redirect(UriComponentsBuilder.fromPath(PRIVACY_PAGE)
                    .queryParam("returnTo", notice.returnTo()).encode().build().toUri());
            case Denied denied -> redirect(back(denied.returnTo(), "recusado"));
            case Failed failed -> redirect(back(failed.returnTo(), "falhou"));
        };
    }

    /** Situação do consumidor neste navegador: conectado, aguardando o aviso de privacidade ou sem login. */
    @GetMapping("/session")
    ConsumerSessionView session(HttpServletRequest request) {
        Optional<SessionWindow> window = session.window(request);
        Optional<GoogleSignIn.ConsumerAccount> account = session.account(request).flatMap(signIn::account);
        if (window.isPresent() && account.isPresent()) {
            return new ConsumerSessionView(true, account.get().email(), window.get().idleDeadline(),
                    window.get().absoluteDeadline(), null, signIn.privacyNoticeVersion());
        }
        return new ConsumerSessionView(false, null, null, null, signIn.pendingEmail(request).orElse(null),
                signIn.privacyNoticeVersion());
    }

    @PostMapping("/privacy/accept")
    ReturnView accept(@RequestBody AcceptRequest body, HttpServletRequest request) {
        return switch (signIn.accept(request, body.noticeVersion())) {
            case AccountCreated created -> new ReturnView(created.returnTo());
            case NothingPending nothing -> throw IdentityProblems.error(HttpStatus.GONE, "LOGIN_GOOGLE_EXPIRADO",
                    "O login com Google expirou. Entre com Google de novo.");
            case OutdatedNotice outdated -> throw IdentityProblems.error(HttpStatus.CONFLICT, "AVISO_DESATUALIZADO",
                    "O aviso de privacidade mudou. Recarregue a página e leia a versão atual.");
        };
    }

    @PostMapping("/privacy/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(HttpServletRequest request) {
        signIn.decline(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        session.signOut(request);
    }

    private static URI back(String returnTo, String result) {
        return UriComponentsBuilder.fromPath(GoogleSignIn.safeReturnPath(returnTo)).queryParam("google", result)
                .build().toUri();
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /**
     * @param pendingEmail  e-mail da conta Google que aguarda o aviso de privacidade (só nesta sessão)
     * @param noticeVersion versão atual do aviso de privacidade
     */
    record ConsumerSessionView(boolean signedIn, String email, Instant idleDeadline, Instant absoluteDeadline,
            String pendingEmail, String noticeVersion) {
    }

    record AcceptRequest(String noticeVersion) {
    }

    record ReturnView(String returnTo) {
    }
}
