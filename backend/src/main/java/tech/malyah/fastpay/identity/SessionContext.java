package tech.malyah.fastpay.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.domain.SessionProfile;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Leitura da identidade autenticada e da unidade escolhida, guardadas na sessão do servidor (Spring
 * Session JDBC; nunca em header ou parâmetro). O login (H08) grava a identidade e a janela de validade;
 * a seleção de unidade (H07) grava a unidade, sempre depois de validar o vínculo. A unidade da sessão é
 * só a escolha: cada operação revalida o vínculo com {@link Memberships#inUnit}.
 */
@Component
public class SessionContext {

    public static final String IDENTITY_ATTRIBUTE = "fastpay.identityId";
    public static final String UNIT_ATTRIBUTE = "fastpay.establishmentId";
    static final String PROFILE_ATTRIBUTE = "fastpay.sessionProfile";
    static final String AUTHENTICATED_AT_ATTRIBUTE = "fastpay.authenticatedAt";
    static final String LAST_ACTIVITY_ATTRIBUTE = "fastpay.lastActivityAt";

    /** @throws AuthenticationRequiredException sem identidade autenticada na sessão */
    public UUID requireIdentity(HttpServletRequest request) {
        return attribute(request, IDENTITY_ATTRIBUTE).orElseThrow(AuthenticationRequiredException::new);
    }

    public Optional<UUID> selectedUnit(HttpServletRequest request) {
        return attribute(request, UNIT_ATTRIBUTE);
    }

    /** @throws UnitNotSelectedException sem unidade escolhida nesta sessão */
    public UUID requireSelectedUnit(HttpServletRequest request) {
        return selectedUnit(request).orElseThrow(UnitNotSelectedException::new);
    }

    /**
     * Grava a unidade já validada e troca o id da sessão, porque o contexto de privilégio mudou
     * (especificação §5).
     */
    public void selectUnit(HttpServletRequest request, UUID establishmentId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthenticationRequiredException();
        }
        request.changeSessionId();
        session.setAttribute(UNIT_ATTRIBUTE, establishmentId);
    }

    /** Esquece a unidade escolhida; a identidade continua autenticada. */
    public void clearUnit(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(UNIT_ATTRIBUTE);
        }
    }

    /**
     * Inicia a sessão autenticada. A sessão anterior, se havia, é descartada inteira e uma nova é criada
     * com outro id (proteção contra fixação de sessão). O id da identidade vira o principal da sessão,
     * para que a redefinição de senha encontre e encerre todas as sessões da pessoa.
     */
    public void signIn(HttpServletRequest request, UUID identityId, SessionProfile profile, Instant now) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(IDENTITY_ATTRIBUTE, identityId);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, identityId.toString());
        session.setAttribute(PROFILE_ATTRIBUTE, profile.name());
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, now);
        session.setAttribute(LAST_ACTIVITY_ATTRIBUTE, now);
        // Limpeza do Spring Session; o prazo efetivo (inatividade humana e absoluto) é o da janela.
        session.setMaxInactiveInterval(Math.toIntExact(profile.idleTimeout().toSeconds()));
    }

    /** Encerra a sessão, se houver. */
    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public boolean hasIdentity(HttpServletRequest request) {
        return attribute(request, IDENTITY_ATTRIBUTE).isPresent();
    }

    /** Janela de validade da sessão autenticada; vazia sem sessão ou com dados incompletos. */
    public Optional<SessionWindow> window(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(PROFILE_ATTRIBUTE) instanceof String profile)
                || !(session.getAttribute(AUTHENTICATED_AT_ATTRIBUTE) instanceof Instant authenticatedAt)
                || !(session.getAttribute(LAST_ACTIVITY_ATTRIBUTE) instanceof Instant lastActivityAt)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SessionWindow(SessionProfile.valueOf(profile), authenticatedAt, lastActivityAt));
        } catch (IllegalArgumentException unknownProfile) {
            return Optional.empty();
        }
    }

    /** Registra atividade da pessoa (não chamar para polling, SSE ou heartbeat). */
    public void recordActivity(HttpServletRequest request, SessionWindow window) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(LAST_ACTIVITY_ATTRIBUTE, window.lastActivityAt());
        }
    }

    private static Optional<UUID> attribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(name) instanceof UUID id ? Optional.of(id) : Optional.empty();
    }
}
