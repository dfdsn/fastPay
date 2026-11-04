package tech.malyah.fastpay.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.application.MultiFactor;
import tech.malyah.fastpay.identity.domain.SessionProfile;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Sessões de quem mantém uma conexão aberta com o servidor (SSE, H17). A conexão começa numa requisição comum, que passa
 * pelos prazos da sessão; depois disso nenhuma requisição nova chega, então quem envia os avisos confere de tempos em
 * tempos, pela própria sessão guardada, se ela ainda vale: encerrada (logout, redefinição de senha, MFA trocado), vencida
 * por inatividade ou pelo prazo absoluto, outra unidade escolhida (troca o id da sessão) ou vínculo desativado. A
 * conferência só lê: não conta como atividade da pessoa nem renova a sessão (especificação §5).
 */
@Component
public class LiveSessions {

    /** A sessão de uma conexão aberta: o id do Spring Session e quem estava nela, na unidade escolhida. */
    public record Live(String sessionId, UUID identityId, UUID establishmentId) {
    }

    /** Por que a conexão não vale mais. */
    public enum Ended {
        /** Sessão encerrada, vencida ou parada na verificação em duas etapas: entrar de novo. */
        SESSAO_ENCERRADA,
        /** Outra unidade escolhida nesta sessão, ou vínculo desativado: escolher a unidade de novo. */
        UNIDADE_NAO_AUTORIZADA
    }

    private final SessionContext session;
    private final Memberships memberships;
    private final MultiFactor mfa;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final Clock clock;

    LiveSessions(SessionContext session, Memberships memberships, MultiFactor mfa,
            FindByIndexNameSessionRepository<?> sessions, Clock clock) {
        this.session = session;
        this.memberships = memberships;
        this.mfa = mfa;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * A sessão desta requisição, na unidade escolhida e com vínculo ativo agora.
     *
     * @throws AuthenticationRequiredException sem sessão autenticada
     * @throws MfaRequiredException            falta a verificação em duas etapas
     * @throws UnitNotSelectedException        sem unidade escolhida
     * @throws UnitAccessDeniedException       sem vínculo ativo na unidade
     */
    public Live current(HttpServletRequest request) {
        UUID identityId = session.requireIdentity(request);
        UUID establishmentId = session.requireSelectedUnit(request);
        memberships.inUnit(identityId, establishmentId, m -> m);
        HttpSession http = request.getSession(false);
        if (http == null) {
            throw new AuthenticationRequiredException();
        }
        return new Live(http.getId(), identityId, establishmentId);
    }

    /** Vazio enquanto a conexão vale; senão, o motivo para encerrá-la. */
    public Optional<Ended> recheck(Live live) {
        Session stored = sessions.findById(live.sessionId());
        if (stored == null) {
            return Optional.of(Ended.SESSAO_ENCERRADA);
        }
        if (!live.identityId().equals(stored.getAttribute(SessionContext.IDENTITY_ATTRIBUTE))
                || expired(stored, clock.instant())
                || mfaNowMissing(stored, live.identityId())) {
            return Optional.of(Ended.SESSAO_ENCERRADA);
        }
        if (!live.establishmentId().equals(stored.getAttribute(SessionContext.UNIT_ATTRIBUTE))) {
            return Optional.of(Ended.UNIDADE_NAO_AUTORIZADA);
        }
        try {
            memberships.inUnit(live.identityId(), live.establishmentId(), m -> m);
        } catch (UnitAccessDeniedException lost) {
            return Optional.of(Ended.UNIDADE_NAO_AUTORIZADA);
        }
        return Optional.empty();
    }

    private static boolean expired(Session stored, Instant now) {
        if (!(stored.getAttribute(SessionContext.PROFILE_ATTRIBUTE) instanceof String profile)
                || !(stored.getAttribute(SessionContext.AUTHENTICATED_AT_ATTRIBUTE) instanceof Instant authenticatedAt)
                || !(stored.getAttribute(SessionContext.LAST_ACTIVITY_ATTRIBUTE) instanceof Instant lastActivityAt)) {
            return true;
        }
        try {
            return new SessionWindow(SessionProfile.valueOf(profile), authenticatedAt, lastActivityAt).expiredAt(now);
        } catch (IllegalArgumentException unknownProfile) {
            return true;
        }
    }

    /** Como no filtro da sessão: quem passou a precisar do segundo fator volta para essa etapa (FR-05, FR-06). */
    private boolean mfaNowMissing(Session stored, UUID identityId) {
        if (Boolean.TRUE.equals(stored.getAttribute(SessionContext.MFA_VERIFIED_ATTRIBUTE))) {
            return false;
        }
        MultiFactor.Requirement requirement = mfa.requirement(identityId);
        return requirement.enrolled() || requirement.required();
    }
}
