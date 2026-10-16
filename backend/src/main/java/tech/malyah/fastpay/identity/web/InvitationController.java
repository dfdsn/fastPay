package tech.malyah.fastpay.identity.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.application.InvitationAcceptance;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.AcceptOutcome;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.Accepted;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.AlreadyMember;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.InvalidData;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.InvalidInvitation;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.OtherAccount;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.SignInRequired;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.Throttled;
import tech.malyah.fastpay.identity.application.InvitationAcceptance.Valid;

/**
 * Tela de aceite de convite (H10, FR-02). O token vem no corpo (o link o leva no fragmento, que não chega ao
 * servidor nem aos logs de acesso). Com sessão autenticada, o aceite é da conta da sessão; sem sessão, cria a
 * conta do e-mail do convite. Mutações exigem o token CSRF, como todo o resto.
 */
@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {

    private final InvitationAcceptance acceptance;
    private final SessionContext session;

    InvitationController(InvitationAcceptance acceptance, SessionContext session) {
        this.acceptance = acceptance;
        this.session = session;
    }

    /** Dados do convite para a tela, sem consumi-lo. POST para o token não ir na URL. */
    @PostMapping("/preview")
    PreviewView preview(@RequestBody TokenRequest body, HttpServletRequest request) {
        return switch (acceptance.preview(body.token(), request.getRemoteAddr())) {
            case Valid valid -> new PreviewView(valid.preview().establishmentName(), valid.preview().role(),
                    valid.preview().role().label(), valid.preview().email(), valid.preview().expiresAt(),
                    valid.preview().accountExists(), session.hasIdentity(request));
            case InvalidInvitation invalid -> throw invalidInvitation();
            case InvitationAcceptance.Throttled throttled -> throw tooManyAttempts();
        };
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    AcceptedView accept(@RequestBody AcceptRequest body, HttpServletRequest request) {
        String correlationId = UUID.randomUUID().toString();
        AcceptOutcome outcome = session.hasIdentity(request)
                ? acceptance.acceptAsSignedIn(body.token(), session.requireIdentity(request), request.getRemoteAddr(),
                        correlationId)
                : acceptance.acceptWithNewAccount(body.token(), body.displayName(), body.password(),
                        request.getRemoteAddr(), correlationId);
        return switch (outcome) {
            case Accepted accepted -> new AcceptedView(accepted.establishmentId(), session.hasIdentity(request));
            case SignInRequired signIn -> throw IdentityProblems.error(HttpStatus.CONFLICT, "ENTRE_PARA_ACEITAR",
                    "Este e-mail já tem conta no fastPay. Entre com ela e abra o link do convite de novo.");
            case OtherAccount other -> throw IdentityProblems.error(HttpStatus.FORBIDDEN, "CONVITE_DE_OUTRA_CONTA",
                    "Este convite foi enviado para outro e-mail. Saia e entre com a conta do e-mail convidado.");
            case AlreadyMember member -> throw IdentityProblems.error(HttpStatus.CONFLICT, "JA_PARTICIPA",
                    "Você já participa desta unidade.");
            case InvalidData data -> throw IdentityProblems.error(HttpStatus.BAD_REQUEST, "DADOS_RECUSADOS",
                    data.reason());
            case InvalidInvitation invalid -> throw invalidInvitation();
            case Throttled throttled -> throw tooManyAttempts();
        };
    }

    private static RuntimeException invalidInvitation() {
        return IdentityProblems.error(HttpStatus.BAD_REQUEST, "CONVITE_INVALIDO",
                "Este convite não vale mais. Peça um novo a quem convidou você.");
    }

    private static RuntimeException tooManyAttempts() {
        return IdentityProblems.error(HttpStatus.TOO_MANY_REQUESTS, "MUITAS_TENTATIVAS",
                "Muitas tentativas. Aguarde alguns minutos e tente novamente.");
    }

    record TokenRequest(String token) {
    }

    record AcceptRequest(String token, String displayName, String password) {
    }

    /** {@code signedIn}: há sessão autenticada neste navegador (o aceite será da conta dela). */
    record PreviewView(String establishmentName, MembershipRole role, String roleLabel, String email, Instant expiresAt,
            boolean accountExists, boolean signedIn) {
    }

    /** {@code signedIn}: aceito com a sessão atual; sem sessão, a pessoa entra com a conta criada. */
    record AcceptedView(UUID establishmentId, boolean signedIn) {
    }
}
