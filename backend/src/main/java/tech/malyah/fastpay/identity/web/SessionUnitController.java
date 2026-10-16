package tech.malyah.fastpay.identity.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.identity.AuthenticationRequiredException;
import tech.malyah.fastpay.identity.IdentitySummary;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.PlatformAccess;
import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.UnitMembership;

/**
 * Seleção da unidade ativa da sessão (H07, FR-03). A unidade pedida só é aceita com vínculo ativo; a
 * unidade guardada é revalidada a cada leitura.
 */
@RestController
@RequestMapping("/api/v1/session")
class SessionUnitController {

    private final Memberships memberships;
    private final SessionContext session;
    private final PlatformAccess platform;

    SessionUnitController(Memberships memberships, SessionContext session, PlatformAccess platform) {
        this.memberships = memberships;
        this.session = session;
        this.platform = platform;
    }

    /**
     * Quem está na sessão, unidades disponíveis e a escolhida, se ainda válida. {@code platformAdmin} mostra o
     * caminho para a área do admin fastPay (H10), que não é uma unidade.
     */
    @GetMapping("/units")
    UnitsResponse units(HttpServletRequest request) {
        UUID identityId = session.requireIdentity(request);
        IdentitySummary identity = memberships.identity(identityId).orElseThrow(AuthenticationRequiredException::new);
        List<UnitMembership> units = memberships.activeUnits(identityId);
        UUID selected = session.selectedUnit(request)
                .filter(id -> units.stream().anyMatch(u -> u.establishmentId().equals(id)))
                .orElse(null);
        if (selected == null) {
            session.clearUnit(request);
        }
        return new UnitsResponse(identity.displayName(), units.stream().map(UnitView::of).toList(), selected,
                platform.isPlatformAdmin(identityId));
    }

    @PutMapping("/unit")
    UnitView select(@RequestBody SelectUnitRequest body, HttpServletRequest request) {
        UUID identityId = session.requireIdentity(request);
        if (body.establishmentId() == null) {
            throw IdentityProblems.invalidRequest("Escolha uma unidade.");
        }
        UnitMembership membership = memberships.inUnit(identityId, body.establishmentId(), m -> m);
        session.selectUnit(request, membership.establishmentId());
        return UnitView.of(membership);
    }

    /** Unidade ativa, revalidada agora. Vínculo desativado desde a escolha resulta em 403 e limpa a escolha. */
    @GetMapping("/unit")
    UnitView current(HttpServletRequest request) {
        UUID identityId = session.requireIdentity(request);
        UUID establishmentId = session.requireSelectedUnit(request);
        return UnitView.of(memberships.inUnit(identityId, establishmentId, m -> m));
    }

    @DeleteMapping("/unit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(HttpServletRequest request) {
        session.requireIdentity(request);
        session.clearUnit(request);
    }

    record SelectUnitRequest(UUID establishmentId) {
    }

    /** {@code permissions}: o que a pessoa pode fazer na unidade agora (papel e delegações), para a tela. */
    record UnitView(UUID establishmentId, String name, MembershipRole role, List<Permission> permissions) {
        static UnitView of(UnitMembership membership) {
            return new UnitView(membership.establishmentId(), membership.establishmentName(), membership.role(),
                    membership.permissions().stream().sorted().toList());
        }
    }

    record UnitsResponse(String displayName, List<UnitView> units, UUID currentEstablishmentId, boolean platformAdmin) {
    }
}
