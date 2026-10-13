package tech.malyah.fastpay.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.domain.AuthStage;
import tech.malyah.fastpay.identity.domain.SessionProfile;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Leitura da identidade autenticada e da unidade escolhida, guardadas na sessão do servidor (Spring
 * Session JDBC; nunca em header ou parâmetro). O login (H08) grava a identidade e a janela de validade,
 * depois da verificação em duas etapas quando ela vale para a pessoa (H09);
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
    static final String MFA_VERIFIED_ATTRIBUTE = "fastpay.mfaVerified";
    static final String PENDING_IDENTITY_ATTRIBUTE = "fastpay.pendingIdentityId";
    static final String PENDING_STAGE_ATTRIBUTE = "fastpay.pendingStage";
    static final String PENDING_SINCE_ATTRIBUTE = "fastpay.pendingSince";
    static final String PENDING_ENROLLMENT_ATTRIBUTE = "fastpay.pendingEnrollment";

    /** Sessão que passou pela senha e ainda não pela verificação em duas etapas (H09). */
    public record PendingAuthentication(UUID identityId, AuthStage stage, Instant since) {
    }

    /**
     * @throws MfaRequiredException            senha certa, mas falta a verificação em duas etapas
     * @throws AuthenticationRequiredException sem identidade autenticada na sessão
     */
    public UUID requireIdentity(HttpServletRequest request) {
        Optional<UUID> identity = attribute(request, IDENTITY_ATTRIBUTE);
        if (identity.isPresent()) {
            return identity.get();
        }
        throw pending(request).<RuntimeException>map(p -> new MfaRequiredException(p.stage()))
                .orElseGet(AuthenticationRequiredException::new);
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
    public void signIn(HttpServletRequest request, UUID identityId, SessionProfile profile, Instant now,
            boolean mfaVerified) {
        HttpSession session = freshSession(request, identityId);
        session.setAttribute(IDENTITY_ATTRIBUTE, identityId);
        session.setAttribute(MFA_VERIFIED_ATTRIBUTE, mfaVerified);
        session.setAttribute(PROFILE_ATTRIBUTE, profile.name());
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, now);
        session.setAttribute(LAST_ACTIVITY_ATTRIBUTE, now);
        // Limpeza do Spring Session; o prazo efetivo (inatividade humana e absoluto) é o da janela.
        session.setMaxInactiveInterval(Math.toIntExact(profile.idleTimeout().toSeconds()));
    }

    /**
     * Sessão nova (id novo) que só guarda a identidade à parte e a etapa que falta; nenhuma função aceita
     * esta sessão ({@link #requireIdentity}). Vale pelo prazo curto de {@code timeout}.
     */
    public void beginPending(HttpServletRequest request, UUID identityId, AuthStage stage, Instant now,
            Duration timeout) {
        HttpSession session = freshSession(request, identityId);
        session.setAttribute(PENDING_IDENTITY_ATTRIBUTE, identityId);
        session.setAttribute(PENDING_STAGE_ATTRIBUTE, stage.name());
        session.setAttribute(PENDING_SINCE_ATTRIBUTE, now);
        session.setMaxInactiveInterval(Math.toIntExact(timeout.toSeconds()));
    }

    public Optional<PendingAuthentication> pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(PENDING_IDENTITY_ATTRIBUTE) instanceof UUID identityId)
                || !(session.getAttribute(PENDING_STAGE_ATTRIBUTE) instanceof String stage)
                || !(session.getAttribute(PENDING_SINCE_ATTRIBUTE) instanceof Instant since)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PendingAuthentication(identityId, AuthStage.valueOf(stage), since));
        } catch (IllegalArgumentException unknownStage) {
            return Optional.empty();
        }
    }

    /** Cadastro de autenticador em andamento nesta sessão: só o segredo cifrado. */
    public void storeEnrollment(HttpServletRequest request, String secretCiphertext) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthenticationRequiredException();
        }
        session.setAttribute(PENDING_ENROLLMENT_ATTRIBUTE, secretCiphertext);
    }

    public Optional<String> enrollment(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(PENDING_ENROLLMENT_ATTRIBUTE) instanceof String ciphertext
                ? Optional.of(ciphertext) : Optional.empty();
    }

    /** A sessão autenticada passou pelo segundo fator (no login ou ao cadastrar o autenticador). */
    public boolean mfaVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(MFA_VERIFIED_ATTRIBUTE));
    }

    /**
     * Sessão já autenticada que acabou de cadastrar o autenticador por vontade própria: passa a contar
     * como verificada e troca de id (privilégio mudou). O início absoluto não muda.
     */
    public void markMfaVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthenticationRequiredException();
        }
        request.changeSessionId();
        session.removeAttribute(PENDING_ENROLLMENT_ATTRIBUTE);
        session.setAttribute(MFA_VERIFIED_ATTRIBUTE, true);
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

    /** Descarta a sessão anterior inteira e cria outra, com id novo e a identidade como principal. */
    private static HttpSession freshSession(HttpServletRequest request, UUID identityId) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, identityId.toString());
        return session;
    }

    private static Optional<UUID> attribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(name) instanceof UUID id ? Optional.of(id) : Optional.empty();
    }
}
