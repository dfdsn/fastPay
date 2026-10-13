package tech.malyah.fastpay.identity.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.AuthenticationRequiredException;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.application.PasswordResets;
import tech.malyah.fastpay.identity.application.PasswordResets.ConfirmOutcome;
import tech.malyah.fastpay.identity.application.PasswordResets.ConfirmThrottled;
import tech.malyah.fastpay.identity.application.PasswordResets.InvalidLink;
import tech.malyah.fastpay.identity.application.PasswordResets.PasswordChanged;
import tech.malyah.fastpay.identity.application.PasswordResets.WeakPassword;
import tech.malyah.fastpay.identity.application.TeamAuthentication;
import tech.malyah.fastpay.identity.application.TeamAuthentication.Authenticated;
import tech.malyah.fastpay.identity.application.TeamAuthentication.Rejected;
import tech.malyah.fastpay.identity.application.TeamAuthentication.Throttled;
import tech.malyah.fastpay.identity.domain.AuthStage;
import tech.malyah.fastpay.identity.domain.SessionWindow;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;

/**
 * Login da equipe, sessão e redefinição de senha (H08, FR-04). Quando a pessoa tem ou precisa de MFA, o
 * login só abre a etapa do segundo fator ({@link MfaController}, H09). Todas as mutações exigem o token CSRF.
 * Mensagens de erro não dizem se o e-mail existe.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String RESET_REQUESTED = "Se o e-mail estiver cadastrado, enviaremos um link para criar uma nova "
            + "senha. O link vale por 30 minutos.";

    private final TeamAuthentication authentication;
    private final PasswordResets resets;
    private final Memberships memberships;
    private final SessionContext session;
    private final AuthProperties properties;
    private final Clock clock;

    AuthController(TeamAuthentication authentication, PasswordResets resets, Memberships memberships,
            SessionContext session, AuthProperties properties, Clock clock) {
        this.authentication = authentication;
        this.resets = resets;
        this.memberships = memberships;
        this.session = session;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/login")
    SessionView login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return switch (authentication.login(body.email(), body.password(), request.getRemoteAddr())) {
            case Authenticated ok when ok.stage() == AuthStage.AUTENTICADO -> {
                Instant now = clock.instant();
                session.signIn(request, ok.identityId(), ok.profile(), now, false);
                yield SessionView.of(ok.displayName(), SessionWindow.startingAt(ok.profile(), now));
            }
            case Authenticated ok -> {
                // Senha certa; o acesso só abre depois do segundo fator ou do cadastro do autenticador.
                session.beginPending(request, ok.identityId(), ok.stage(), clock.instant(),
                        properties.mfaPendingTimeout());
                yield SessionView.pending(ok.displayName(), ok.stage());
            }
            case Rejected rejected -> throw IdentityProblems.error(HttpStatus.UNAUTHORIZED, "CREDENCIAIS_INVALIDAS",
                    "E-mail ou senha incorretos.");
            case Throttled throttled -> throw tooManyAttempts();
        };
    }

    /**
     * Sessão atual. Prazos já aplicados pelo filtro: sessão vencida chega aqui sem identidade. Sessão na
     * etapa do MFA responde com a etapa, sem prazos, para a tela levar à verificação ou ao cadastro.
     */
    @GetMapping("/session")
    SessionView current(HttpServletRequest request) {
        var pending = session.pending(request);
        if (pending.isPresent()) {
            IdentitySummary identity = memberships.identity(pending.get().identityId())
                    .orElseThrow(AuthenticationRequiredException::new);
            return SessionView.pending(identity.displayName(), pending.get().stage());
        }
        var identityId = session.requireIdentity(request);
        IdentitySummary identity = memberships.identity(identityId).orElseThrow(AuthenticationRequiredException::new);
        SessionWindow window = session.window(request).orElseThrow(AuthenticationRequiredException::new);
        return SessionView.of(identity.displayName(), window);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        session.signOut(request);
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> requestReset(@RequestBody ResetRequest body, HttpServletRequest request) {
        return switch (resets.request(body.email(), request.getRemoteAddr())) {
            case ACCEPTED -> Map.of("detail", RESET_REQUESTED);
            case THROTTLED -> throw tooManyAttempts();
        };
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmReset(@RequestBody ResetConfirmation body, HttpServletRequest request) {
        ConfirmOutcome outcome = resets.confirm(body.token(), body.newPassword(), request.getRemoteAddr());
        switch (outcome) {
            case PasswordChanged changed -> {
                // Esta requisição também deixa de ter sessão: entra de novo com a senha nova.
                session.signOut(request);
            }
            case InvalidLink invalid -> throw IdentityProblems.error(HttpStatus.BAD_REQUEST, "LINK_INVALIDO",
                    "Este link de redefinição não vale mais. Peça um novo.");
            case WeakPassword weak -> throw IdentityProblems.error(HttpStatus.BAD_REQUEST, "SENHA_RECUSADA",
                    weak.reason());
            case ConfirmThrottled throttled -> throw tooManyAttempts();
        }
    }

    private static RuntimeException tooManyAttempts() {
        return IdentityProblems.error(HttpStatus.TOO_MANY_REQUESTS, "MUITAS_TENTATIVAS",
                "Muitas tentativas. Aguarde alguns minutos e tente novamente.");
    }

    record LoginRequest(String email, String password) {
    }

    record ResetRequest(String email) {
    }

    record ResetConfirmation(String token, String newPassword) {
    }

    /**
     * Quem está na sessão, em que etapa (H09) e quando ela vence. {@code idleTimeoutSeconds} permite ao
     * frontend avisar antes de a sessão expirar por inatividade. Na etapa do MFA não há prazos de sessão.
     */
    record SessionView(AuthStage stage, String displayName, Long idleTimeoutSeconds, Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
        static SessionView of(String displayName, SessionWindow window) {
            return new SessionView(AuthStage.AUTENTICADO, displayName, window.profile().idleTimeout().toSeconds(),
                    window.idleDeadline(), window.absoluteDeadline());
        }

        static SessionView pending(String displayName, AuthStage stage) {
            return new SessionView(stage, displayName, null, null, null);
        }
    }
}
