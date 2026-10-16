package tech.malyah.fastpay.identity.web;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.Invitations;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.Team;
import tech.malyah.fastpay.identity.Team.Done;
import tech.malyah.fastpay.identity.Team.Outcome;
import tech.malyah.fastpay.identity.Team.Refused;
import tech.malyah.fastpay.identity.domain.AccessPolicy;

/**
 * Equipe da unidade escolhida na sessão (H11). Ver a equipe: gerente e administrador; convidar, trocar papel e
 * desativar: administrador do estabelecimento; delegar permissões a garçons: gerente e administrador. A unidade
 * vem só da sessão e a permissão é conferida no servidor a cada chamada: 403 {@code PERMISSAO_NEGADA} sem ela.
 */
@RestController
@RequestMapping("/api/v1/team")
class TeamController {

    private final Team team;
    private final SessionContext session;

    TeamController(Team team, SessionContext session) {
        this.team = team;
        this.session = session;
    }

    @GetMapping
    TeamView overview(HttpServletRequest request) {
        return TeamView.of(team.overview(session.requireIdentity(request), session.requireSelectedUnit(request)));
    }

    @PostMapping("/invitations")
    TeamView invite(@RequestBody InviteRequest body, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        return respond(team.invite(actorId, unit, body.email(), role(body.role()), correlationId()));
    }

    @PostMapping("/invitations/{id}/resend")
    TeamView resend(@PathVariable UUID id, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return respond(team.resendInvitation(actorId, session.requireSelectedUnit(request), id, correlationId()));
    }

    @DeleteMapping("/invitations/{id}")
    TeamView cancel(@PathVariable UUID id, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return respond(team.cancelInvitation(actorId, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PutMapping("/members/{id}/role")
    TeamView changeRole(@PathVariable UUID id, @RequestBody RoleRequest body, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        return respond(team.changeRole(actorId, unit, id, role(body.role()), correlationId()));
    }

    @PostMapping("/members/{id}/deactivate")
    TeamView deactivate(@PathVariable UUID id, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return respond(team.deactivate(actorId, session.requireSelectedUnit(request), id, correlationId()));
    }

    @PutMapping("/members/{id}/permissions")
    TeamView permissions(@PathVariable UUID id, @RequestBody PermissionsRequest body, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        if (body.permissions() == null) {
            throw IdentityProblems.invalidRequest("Informe as permissões.");
        }
        Set<Permission> wanted = EnumSet.noneOf(Permission.class);
        for (String code : body.permissions()) {
            try {
                wanted.add(Permission.valueOf(String.valueOf(code)));
            } catch (IllegalArgumentException unknown) {
                throw IdentityProblems.invalidRequest("Permissão desconhecida.");
            }
        }
        return respond(team.setPermissions(actorId, unit, id, wanted, correlationId()));
    }

    private static TeamView respond(Outcome outcome) {
        return switch (outcome) {
            case Done done -> TeamView.of(done.overview());
            case Refused refused -> throw refusal(refused.refusal());
        };
    }

    private static ErrorResponseException refusal(Team.Refusal refusal) {
        return switch (refusal) {
            case VINCULO_NAO_ENCONTRADO -> IdentityProblems.error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Pessoa não encontrada na equipe desta unidade.");
            case CONVITE_NAO_ENCONTRADO -> IdentityProblems.error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Convite não encontrado ou já encerrado.");
            case PROPRIO_VINCULO -> IdentityProblems.error(HttpStatus.UNPROCESSABLE_CONTENT, refusal.name(),
                    "Você não pode alterar o próprio perfil. Peça a outro administrador.");
            case ULTIMO_ADMIN -> IdentityProblems.error(HttpStatus.CONFLICT, refusal.name(),
                    "A unidade precisa de pelo menos um administrador ativo. Nomeie outro antes.");
            case JA_PARTICIPA -> IdentityProblems.error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta pessoa já participa da equipe desta unidade.");
            case PAPEL_NAO_RECEBE -> IdentityProblems.error(HttpStatus.UNPROCESSABLE_CONTENT, refusal.name(),
                    "Permissões específicas são só para garçons.");
            case NAO_DELEGAVEL -> IdentityProblems.error(HttpStatus.UNPROCESSABLE_CONTENT, refusal.name(),
                    "Esta ação não pode ser delegada.");
            case EMAIL_INVALIDO -> IdentityProblems.error(HttpStatus.BAD_REQUEST, refusal.name(),
                    "Informe um e-mail válido.");
        };
    }

    private static MembershipRole role(String value) {
        try {
            return MembershipRole.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            throw IdentityProblems.invalidRequest("Escolha um papel.");
        }
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    record InviteRequest(String email, String role) {
    }

    record RoleRequest(String role) {
    }

    record PermissionsRequest(List<String> permissions) {
    }

    record PermissionView(Permission code, String label) {
        static PermissionView of(Permission permission) {
            return new PermissionView(permission, permission.label());
        }
    }

    record MemberView(UUID membershipId, String displayName, String email, MembershipRole role, boolean active,
            Instant since, Instant deactivatedAt, List<Permission> delegated) {
    }

    record InvitationView(UUID id, String email, MembershipRole role, Invitations.Status status, Instant createdAt,
            Instant sentAt, Instant expiresAt) {
    }

    /**
     * {@code delegable}: permissões que podem ser dadas a um garçom, com o nome para a tela;
     * {@code currentMembershipId}: o vínculo de quem pediu, que a tela não oferece para alterar.
     */
    record TeamView(List<MemberView> members, List<InvitationView> pendingInvitations,
            List<PermissionView> delegable, UUID currentMembershipId) {
        static TeamView of(Team.Overview overview) {
            return new TeamView(
                    overview.members().stream().map(m -> new MemberView(m.membershipId(), m.displayName(), m.email(),
                            m.role(), m.active(), m.since(), m.deactivatedAt(), m.delegated())).toList(),
                    overview.pendingInvitations().stream().map(i -> new InvitationView(i.id(), i.email(), i.role(),
                            i.status(), i.createdAt(), i.sentAt(), i.expiresAt())).toList(),
                    AccessPolicy.delegable().stream().map(PermissionView::of).toList(),
                    overview.currentMembershipId());
        }
    }
}
