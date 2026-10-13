package tech.malyah.fastpay.identity.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.AuthenticationRequiredException;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.MfaRequiredException;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.SessionContext.PendingAuthentication;
import tech.malyah.fastpay.identity.application.MultiFactor;
import tech.malyah.fastpay.identity.application.MultiFactor.CodeRejected;
import tech.malyah.fastpay.identity.application.MultiFactor.CodeThrottled;
import tech.malyah.fastpay.identity.application.MultiFactor.EnrollCodeRejected;
import tech.malyah.fastpay.identity.application.MultiFactor.EnrollThrottled;
import tech.malyah.fastpay.identity.application.MultiFactor.Enrolled;
import tech.malyah.fastpay.identity.application.MultiFactor.Enrollment;
import tech.malyah.fastpay.identity.application.MultiFactor.RecoveryCodeAccepted;
import tech.malyah.fastpay.identity.application.MultiFactor.Requirement;
import tech.malyah.fastpay.identity.application.MultiFactor.TotpAccepted;
import tech.malyah.fastpay.identity.domain.AuthStage;
import tech.malyah.fastpay.identity.domain.SessionWindow;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.web.AuthController.SessionView;

/**
 * Verificação em duas etapas da equipe (H09, FR-05). Depois da senha, a sessão fica numa etapa
 * intermediária até o código do autenticador ({@code /verify}) ou o cadastro do autenticador
 * ({@code /setup} e {@code /setup/confirm}). Código de recuperação no {@code /verify} só leva ao
 * recadastro. Quem já está autenticado sem MFA (garçom, produção) pode ativá-lo pelo mesmo cadastro.
 * Todas as mutações exigem CSRF.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
class MfaController {

    private final MultiFactor mfa;
    private final Memberships memberships;
    private final SessionContext session;
    private final AuthProperties properties;
    private final Clock clock;

    MfaController(MultiFactor mfa, Memberships memberships, SessionContext session, AuthProperties properties,
            Clock clock) {
        this.mfa = mfa;
        this.memberships = memberships;
        this.session = session;
        this.properties = properties;
        this.clock = clock;
    }

    /** Situação do MFA de quem está autenticado. */
    @GetMapping
    StatusView status(HttpServletRequest request) {
        Requirement requirement = mfa.requirement(session.requireIdentity(request));
        return new StatusView(requirement.enrolled(), requirement.required());
    }

    @PostMapping("/verify")
    SessionView verify(@RequestBody CodeRequest body, HttpServletRequest request) {
        PendingAuthentication pending = requirePending(request);
        if (pending.stage() != AuthStage.MFA_PENDENTE) {
            throw new MfaRequiredException(pending.stage());
        }
        UUID identityId = pending.identityId();
        return switch (mfa.verify(identityId, body.code(), request.getRemoteAddr())) {
            case TotpAccepted accepted -> signIn(request, identityId);
            case RecoveryCodeAccepted accepted -> {
                session.beginPending(request, identityId, AuthStage.MFA_RECADASTRO, clock.instant(),
                        properties.mfaPendingTimeout());
                yield SessionView.pending(displayName(identityId), AuthStage.MFA_RECADASTRO);
            }
            case CodeRejected rejected -> throw invalidCode();
            case CodeThrottled throttled -> throw tooManyAttempts();
        };
    }

    /**
     * Inicia o cadastro do autenticador: devolve o segredo para o aplicativo, uma única vez por pedido.
     * Pedir de novo troca o segredo em andamento.
     */
    @PostMapping("/setup")
    SetupView setup(HttpServletRequest request) {
        UUID identityId = enrollingIdentity(request);
        Enrollment enrollment = mfa.startEnrollment(identityId);
        session.storeEnrollment(request, enrollment.secretCiphertext());
        return new SetupView(enrollment.secret(), enrollment.provisioningUri());
    }

    /** Confirma o cadastro com um código do autenticador novo e devolve os dez códigos de recuperação. */
    @PostMapping("/setup/confirm")
    EnrolledView confirm(@RequestBody CodeRequest body, HttpServletRequest request) {
        UUID identityId = enrollingIdentity(request);
        String ciphertext = session.enrollment(request).orElseThrow(() -> IdentityProblems.error(HttpStatus.CONFLICT,
                "CADASTRO_NAO_INICIADO", "Comece a configuração do autenticador de novo."));
        return switch (mfa.completeEnrollment(identityId, ciphertext, body.code(), request.getRemoteAddr())) {
            case Enrolled enrolled -> {
                boolean wasPending = session.pending(request).isPresent();
                SessionView view;
                if (wasPending || enrolled.replaced()) {
                    view = signIn(request, identityId);
                } else {
                    session.markMfaVerified(request);
                    view = SessionView.of(displayName(identityId),
                            session.window(request).orElseThrow(AuthenticationRequiredException::new));
                }
                yield new EnrolledView(enrolled.recoveryCodes(), view);
            }
            case EnrollCodeRejected rejected -> throw invalidCode();
            case EnrollThrottled throttled -> throw tooManyAttempts();
        };
    }

    /** Etapa do MFA ou, se a sessão está autenticada, recusa quem já tem MFA (troca só pelo recadastro). */
    private UUID enrollingIdentity(HttpServletRequest request) {
        Optional<PendingAuthentication> pending = session.pending(request);
        if (pending.isPresent()) {
            if (!pending.get().stage().allowsEnrollment()) {
                throw new MfaRequiredException(pending.get().stage());
            }
            return pending.get().identityId();
        }
        UUID identityId = session.requireIdentity(request);
        if (mfa.requirement(identityId).enrolled()) {
            throw IdentityProblems.error(HttpStatus.CONFLICT, "MFA_JA_ATIVO",
                    "A verificação em duas etapas já está ativa nesta conta.");
        }
        return identityId;
    }

    private PendingAuthentication requirePending(HttpServletRequest request) {
        return session.pending(request).orElseThrow(() -> {
            if (session.hasIdentity(request)) {
                return IdentityProblems.error(HttpStatus.CONFLICT, "MFA_NAO_PENDENTE",
                        "Sua sessão já está confirmada.");
            }
            return new AuthenticationRequiredException();
        });
    }

    /** Segundo fator confirmado: sessão nova, com id novo e prazos do perfil atual. */
    private SessionView signIn(HttpServletRequest request, UUID identityId) {
        Instant now = clock.instant();
        Requirement requirement = mfa.requirement(identityId);
        session.signIn(request, identityId, requirement.profile(), now, true);
        return SessionView.of(displayName(identityId), SessionWindow.startingAt(requirement.profile(), now));
    }

    private String displayName(UUID identityId) {
        return memberships.identity(identityId).map(IdentitySummary::displayName)
                .orElseThrow(AuthenticationRequiredException::new);
    }

    private static RuntimeException invalidCode() {
        return IdentityProblems.error(HttpStatus.BAD_REQUEST, "CODIGO_INVALIDO",
                "Código incorreto ou já usado. Confira o aplicativo e tente de novo.");
    }

    private static RuntimeException tooManyAttempts() {
        return IdentityProblems.error(HttpStatus.TOO_MANY_REQUESTS, "MUITAS_TENTATIVAS",
                "Muitas tentativas. Aguarde alguns minutos e tente novamente.");
    }

    record CodeRequest(String code) {
    }

    record StatusView(boolean enabled, boolean required) {
    }

    /** Segredo em grupos de quatro e endereço {@code otpauth://} para o aplicativo. */
    record SetupView(String secret, String otpauthUri) {
    }

    /** Códigos de recuperação em claro: só nesta resposta. */
    record EnrolledView(List<String> recoveryCodes, SessionView session) {
    }
}
