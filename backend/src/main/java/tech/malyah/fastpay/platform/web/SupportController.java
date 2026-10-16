package tech.malyah.fastpay.platform.web;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.Invitations;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.Team;
import tech.malyah.fastpay.platform.SupportAccess;
import tech.malyah.fastpay.platform.SupportAccess.Done;
import tech.malyah.fastpay.platform.SupportAccess.GrantView;
import tech.malyah.fastpay.platform.SupportAccess.Outcome;
import tech.malyah.fastpay.platform.SupportAccess.Refused;
import tech.malyah.fastpay.platform.SupportNotAuthorizedException;

/**
 * Suporte autorizado (H11, FR-66, CA-28), em dois lados.
 *
 * <ul>
 * <li>{@code /api/v1/support-grants}: o dono (administrador do estabelecimento) da unidade da sessão concede,
 * lista e encerra; 403 {@code PERMISSAO_NEGADA} para os demais papéis.
 * <li>{@code /api/v1/support}: a pessoa de suporte (admin fastPay) vê as concessões vigentes dela e consulta a
 * unidade autorizada, só leitura e auditada; sem concessão vigente, 403 {@code SUPORTE_NAO_AUTORIZADO}, a mesma
 * resposta para unidade inexistente, alheia, vencida ou encerrada.
 * </ul>
 */
@RestController
class SupportController {

    private final SupportAccess support;
    private final Team team;
    private final SessionContext session;

    SupportController(SupportAccess support, Team team, SessionContext session) {
        this.support = support;
        this.team = team;
        this.session = session;
    }

    // --- Dono ---

    @GetMapping("/api/v1/support-grants")
    GrantsView grants(HttpServletRequest request) {
        return GrantsView.of(support.grants(session.requireIdentity(request), session.requireSelectedUnit(request)),
                support.maxDuration());
    }

    @PostMapping("/api/v1/support-grants")
    GrantsView grant(@RequestBody GrantRequest body, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        Duration duration = body.hours() == null ? null : Duration.ofHours(body.hours());
        return respond(support.grant(actorId, unit, body.supportEmail(), body.reason(), duration,
                UUID.randomUUID().toString()));
    }

    @PostMapping("/api/v1/support-grants/{id}/revoke")
    GrantsView revoke(@PathVariable UUID id, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return respond(support.revoke(actorId, session.requireSelectedUnit(request), id, UUID.randomUUID().toString()));
    }

    // --- Suporte ---

    @GetMapping("/api/v1/support/grants")
    List<ActiveGrantView> activeGrants(HttpServletRequest request) {
        return support.activeGrantsOf(session.requireIdentity(request)).stream().map(ActiveGrantView::of).toList();
    }

    /** Equipe e convites da unidade autorizada, só leitura. */
    @GetMapping("/api/v1/support/units/{establishmentId}/team")
    SupportTeamView team(@PathVariable UUID establishmentId, HttpServletRequest request) {
        UUID supportId = session.requireIdentity(request);
        return SupportTeamView.of(support.consult(supportId, establishmentId, "equipe", team::overviewInCurrentUnit));
    }

    @ExceptionHandler(SupportNotAuthorizedException.class)
    ProblemDetail notAuthorized() {
        return problem(HttpStatus.FORBIDDEN, "SUPORTE_NAO_AUTORIZADO",
                "Sem autorização do dono para consultar esta unidade, ou a autorização venceu.");
    }

    private GrantsView respond(Outcome outcome) {
        return switch (outcome) {
            case Done done -> GrantsView.of(done.grants(), support.maxDuration());
            case Refused refused -> throw switch (refused.refusal()) {
                case NAO_E_SUPORTE -> error(HttpStatus.BAD_REQUEST, "NAO_E_SUPORTE",
                        "Este e-mail não é de uma pessoa da equipe fastPay.");
                case JA_AUTORIZADO -> error(HttpStatus.CONFLICT, "SUPORTE_JA_AUTORIZADO",
                        "Esta pessoa já tem acesso vigente. Encerre o acesso atual antes de conceder outro.");
                case MOTIVO_INVALIDO -> error(HttpStatus.BAD_REQUEST, "MOTIVO_INVALIDO",
                        "Explique o motivo do acesso, com 5 a 500 caracteres.");
                case PRAZO_INVALIDO -> error(HttpStatus.BAD_REQUEST, "PRAZO_INVALIDO",
                        "Escolha um prazo entre 1 e %d horas.".formatted(support.maxDuration().toHours()));
                case CONCESSAO_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, "CONCESSAO_NAO_ENCONTRADA",
                        "Acesso de suporte não encontrado ou já encerrado.");
            };
        };
    }

    record GrantRequest(String supportEmail, String reason, Integer hours) {
    }

    record GrantsView(List<GrantView> grants, long maxHours) {
        static GrantsView of(List<GrantView> grants, Duration max) {
            return new GrantsView(grants, max.toHours());
        }
    }

    record ActiveGrantView(UUID id, UUID establishmentId, String establishmentName, String reason, Instant grantedAt,
            Instant expiresAt) {
        static ActiveGrantView of(GrantView g) {
            return new ActiveGrantView(g.id(), g.establishmentId(), g.establishmentName(), g.reason(), g.grantedAt(),
                    g.expiresAt());
        }
    }

    record SupportMemberView(String displayName, String email, MembershipRole role, boolean active,
            List<Permission> delegated) {
    }

    record SupportInvitationView(String email, MembershipRole role, Invitations.Status status, Instant expiresAt) {
    }

    record SupportTeamView(List<SupportMemberView> members, List<SupportInvitationView> pendingInvitations) {
        static SupportTeamView of(Team.Overview overview) {
            return new SupportTeamView(
                    overview.members().stream().map(m -> new SupportMemberView(m.displayName(), m.email(), m.role(),
                            m.active(), m.delegated())).toList(),
                    overview.pendingInvitations().stream().map(i -> new SupportInvitationView(i.email(), i.role(),
                            i.status(), i.expiresAt())).toList());
        }
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        return new ErrorResponseException(status, problem(status, code, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return problem;
    }
}
