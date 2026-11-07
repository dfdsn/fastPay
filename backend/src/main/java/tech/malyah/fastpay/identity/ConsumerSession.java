package tech.malyah.fastpay.identity;

import java.time.Duration;
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
 * Sessão do consumidor que entrou com Google (H27, FR-04, FR-10, P-13), guardada no servidor como a da equipe, mas
 * com atributos próprios: uma sessão é da equipe ou do consumidor, nunca das duas. Entrar como consumidor descarta a
 * sessão anterior (inclusive a da equipe) e o login da equipe descarta a do consumidor ({@link SessionContext}), então
 * nenhum vínculo ou privilégio passa de uma conta para a outra, mesmo com o mesmo e-mail. Prazos de 7 dias parado e
 * 30 dias no total ({@link SessionProfile#CONSUMIDOR}).
 *
 * <p>Também guarda, antes do login, o pedido em andamento ao Google (state, nonce e verificador PKCE, uso único e
 * 10 minutos, T-03) e, entre o retorno do Google e o aceite do aviso de privacidade, a identidade Google ainda sem
 * conta. Nada disso vai para o navegador.
 */
@Component
public class ConsumerSession {

    static final String ACCOUNT_ATTRIBUTE = "fastpay.consumerAccountId";
    static final String AUTHENTICATED_AT_ATTRIBUTE = "fastpay.consumerAuthenticatedAt";
    static final String LAST_ACTIVITY_ATTRIBUTE = "fastpay.consumerLastActivityAt";
    static final String OAUTH_STATE_ATTRIBUTE = "fastpay.googleState";
    static final String OAUTH_NONCE_ATTRIBUTE = "fastpay.googleNonce";
    static final String OAUTH_VERIFIER_ATTRIBUTE = "fastpay.googleVerifier";
    static final String OAUTH_RETURN_ATTRIBUTE = "fastpay.googleReturnTo";
    static final String OAUTH_SINCE_ATTRIBUTE = "fastpay.googleSince";
    static final String SIGNUP_SUBJECT_ATTRIBUTE = "fastpay.googleSignupSubject";
    static final String SIGNUP_EMAIL_ATTRIBUTE = "fastpay.googleSignupEmail";
    static final String SIGNUP_VERIFIED_ATTRIBUTE = "fastpay.googleSignupVerified";
    static final String SIGNUP_RETURN_ATTRIBUTE = "fastpay.googleSignupReturnTo";
    static final String SIGNUP_SINCE_ATTRIBUTE = "fastpay.googleSignupSince";

    /** Pedido ao Google em andamento neste navegador. */
    public record OAuthRequest(String state, String nonce, String verifier, String returnTo, Instant since) {
    }

    /** Identidade Google confirmada que ainda não tem conta: falta aceitar o aviso de privacidade. */
    public record PendingSignup(String subject, String email, boolean emailVerified, String returnTo, Instant since) {
    }

    /** Conta do consumidor desta sessão, se houver. */
    public Optional<UUID> account(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(ACCOUNT_ATTRIBUTE) instanceof UUID id ? Optional.of(id)
                : Optional.empty();
    }

    /** @throws ConsumerSignInRequiredException sem consumidor conectado nesta sessão */
    public UUID requireAccount(HttpServletRequest request) {
        return account(request).orElseThrow(ConsumerSignInRequiredException::new);
    }

    /** Janela de validade da sessão do consumidor; vazia sem consumidor ou com dados incompletos. */
    public Optional<SessionWindow> window(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ACCOUNT_ATTRIBUTE) instanceof UUID)
                || !(session.getAttribute(AUTHENTICATED_AT_ATTRIBUTE) instanceof Instant since)
                || !(session.getAttribute(LAST_ACTIVITY_ATTRIBUTE) instanceof Instant last)) {
            return Optional.empty();
        }
        return Optional.of(new SessionWindow(SessionProfile.CONSUMIDOR, since, last));
    }

    /** Registra ação da pessoa (não chamar para polling, SSE ou heartbeat). */
    public void recordActivity(HttpServletRequest request, SessionWindow window) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(LAST_ACTIVITY_ATTRIBUTE, window.lastActivityAt());
        }
    }

    /**
     * Inicia a sessão do consumidor: descarta a sessão anterior inteira (inclusive uma da equipe) e cria outra com id
     * novo (fixação de sessão).
     */
    public void signIn(HttpServletRequest request, UUID accountId, Instant now) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "consumidor:" + accountId);
        session.setAttribute(ACCOUNT_ATTRIBUTE, accountId);
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, now);
        session.setAttribute(LAST_ACTIVITY_ATTRIBUTE, now);
        session.setMaxInactiveInterval(Math.toIntExact(SessionProfile.CONSUMIDOR.idleTimeout().toSeconds()));
    }

    /** Sai da conta do consumidor; a sessão inteira é encerrada. */
    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(ACCOUNT_ATTRIBUTE) != null) {
            session.invalidate();
        }
    }

    /**
     * Guarda o pedido ao Google, substituindo um anterior (só o último vale). Usa a sessão existente, sem mexer no que
     * ela já tem; sem sessão, cria uma curta, só para o pedido.
     */
    public void beginOAuth(HttpServletRequest request, OAuthRequest oauth, Duration ttl) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            session = request.getSession(true);
            session.setMaxInactiveInterval(Math.toIntExact(ttl.toSeconds()));
        }
        session.setAttribute(OAUTH_STATE_ATTRIBUTE, oauth.state());
        session.setAttribute(OAUTH_NONCE_ATTRIBUTE, oauth.nonce());
        session.setAttribute(OAUTH_VERIFIER_ATTRIBUTE, oauth.verifier());
        session.setAttribute(OAUTH_RETURN_ATTRIBUTE, oauth.returnTo());
        session.setAttribute(OAUTH_SINCE_ATTRIBUTE, oauth.since());
    }

    /** Retira o pedido ao Google desta sessão: cada pedido vale uma vez, certo ou errado o retorno. */
    public Optional<OAuthRequest> takeOAuth(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object state = session.getAttribute(OAUTH_STATE_ATTRIBUTE);
        Object nonce = session.getAttribute(OAUTH_NONCE_ATTRIBUTE);
        Object verifier = session.getAttribute(OAUTH_VERIFIER_ATTRIBUTE);
        Object returnTo = session.getAttribute(OAUTH_RETURN_ATTRIBUTE);
        Object since = session.getAttribute(OAUTH_SINCE_ATTRIBUTE);
        session.removeAttribute(OAUTH_STATE_ATTRIBUTE);
        session.removeAttribute(OAUTH_NONCE_ATTRIBUTE);
        session.removeAttribute(OAUTH_VERIFIER_ATTRIBUTE);
        session.removeAttribute(OAUTH_RETURN_ATTRIBUTE);
        session.removeAttribute(OAUTH_SINCE_ATTRIBUTE);
        if (state instanceof String s && nonce instanceof String n && verifier instanceof String v
                && returnTo instanceof String r && since instanceof Instant at) {
            return Optional.of(new OAuthRequest(s, n, v, r, at));
        }
        return Optional.empty();
    }

    /** Guarda a identidade Google confirmada até a pessoa aceitar ou recusar o aviso de privacidade. */
    public void holdSignup(HttpServletRequest request, PendingSignup signup) {
        HttpSession session = request.getSession(true);
        session.setAttribute(SIGNUP_SUBJECT_ATTRIBUTE, signup.subject());
        session.setAttribute(SIGNUP_EMAIL_ATTRIBUTE, signup.email());
        session.setAttribute(SIGNUP_VERIFIED_ATTRIBUTE, signup.emailVerified());
        session.setAttribute(SIGNUP_RETURN_ATTRIBUTE, signup.returnTo());
        session.setAttribute(SIGNUP_SINCE_ATTRIBUTE, signup.since());
    }

    public Optional<PendingSignup> signup(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(SIGNUP_SUBJECT_ATTRIBUTE) instanceof String subject)
                || !(session.getAttribute(SIGNUP_EMAIL_ATTRIBUTE) instanceof String email)
                || !(session.getAttribute(SIGNUP_VERIFIED_ATTRIBUTE) instanceof Boolean verified)
                || !(session.getAttribute(SIGNUP_RETURN_ATTRIBUTE) instanceof String returnTo)
                || !(session.getAttribute(SIGNUP_SINCE_ATTRIBUTE) instanceof Instant since)) {
            return Optional.empty();
        }
        return Optional.of(new PendingSignup(subject, email, verified, returnTo, since));
    }

    public void clearSignup(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SIGNUP_SUBJECT_ATTRIBUTE);
            session.removeAttribute(SIGNUP_EMAIL_ATTRIBUTE);
            session.removeAttribute(SIGNUP_VERIFIED_ATTRIBUTE);
            session.removeAttribute(SIGNUP_RETURN_ATTRIBUTE);
            session.removeAttribute(SIGNUP_SINCE_ATTRIBUTE);
        }
    }
}
