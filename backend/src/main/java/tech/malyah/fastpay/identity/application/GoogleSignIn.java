package tech.malyah.fastpay.identity.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.ConsumerSession;
import tech.malyah.fastpay.identity.ConsumerSession.OAuthRequest;
import tech.malyah.fastpay.identity.ConsumerSession.PendingSignup;
import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.infrastructure.ConsumerProperties;
import tech.malyah.fastpay.identity.infrastructure.GoogleIdTokenVerifier.GoogleIdentity;
import tech.malyah.fastpay.identity.infrastructure.GoogleOidcClient;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore.Kind;
import tech.malyah.fastpay.identity.infrastructure.JdbcConsumerAccountStore;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Login Google opcional do consumidor (H27, FR-04, FR-10, P-13 aprovada em 07/11/2026). O pedido ao Google leva state,
 * nonce e PKCE guardados na sessão do servidor, de uso único e 10 minutos; o retorno só vale com o state certo. Quem já
 * tem conta entra; quem não tem vê o aviso de privacidade e só então a conta é criada, com a versão aceita. Recusar o
 * aviso, negar no Google ou qualquer falha deixam a pessoa como avulsa, com a comanda funcionando. A conta é separada
 * da equipe: o e-mail não é chave nem junta contas.
 */
@Service
public class GoogleSignIn {

    private static final Logger log = LoggerFactory.getLogger(GoogleSignIn.class);

    /** Para onde voltar depois do login: só telas do consumidor, nunca outro site. */
    static final String DEFAULT_RETURN = "/minhas-comandas";

    public sealed interface StartOutcome {
    }

    /** Mandar o navegador para o Google. */
    public record RedirectToGoogle(URI uri) implements StartOutcome {
    }

    /** Login Google não configurado neste ambiente. */
    public record Unavailable() implements StartOutcome {
    }

    /** Muitos logins iniciados por esta origem (T-03). */
    public record Throttled() implements StartOutcome {
    }

    public sealed interface CallbackOutcome {
        String returnTo();
    }

    /** Conta existente: sessão do consumidor aberta. */
    public record SignedIn(String returnTo) implements CallbackOutcome {
    }

    /** Identidade Google confirmada e sem conta: mostrar o aviso de privacidade. */
    public record PrivacyNoticeRequired(String returnTo) implements CallbackOutcome {
    }

    /** A pessoa negou no Google. */
    public record Denied(String returnTo) implements CallbackOutcome {
    }

    /** State ausente, vencido, repetido ou diferente; troca do código ou token inválidos. */
    public record Failed(String returnTo) implements CallbackOutcome {
    }

    public sealed interface AcceptOutcome {
    }

    public record AccountCreated(String returnTo) implements AcceptOutcome {
    }

    /** Sem identidade Google aguardando o aviso (ou passou dos 10 minutos). */
    public record NothingPending() implements AcceptOutcome {
    }

    /** Aceitou outra versão do aviso, não a atual. */
    public record OutdatedNotice(String currentVersion) implements AcceptOutcome {
    }

    /** Conta do consumidor como a própria pessoa a vê. */
    public record ConsumerAccount(UUID id, String email, Instant createdAt) {
    }

    private final GoogleOidcClient google;
    private final ConsumerSession session;
    private final JdbcConsumerAccountStore accounts;
    private final JdbcAuthAttemptStore attempts;
    private final TenantTransactions tenants;
    private final ConsumerProperties properties;
    private final Clock clock;

    GoogleSignIn(GoogleOidcClient google, ConsumerSession session, JdbcConsumerAccountStore accounts,
            JdbcAuthAttemptStore attempts, TenantTransactions tenants, ConsumerProperties properties, Clock clock) {
        this.google = google;
        this.session = session;
        this.accounts = accounts;
        this.attempts = attempts;
        this.tenants = tenants;
        this.properties = properties;
        this.clock = clock;
    }

    public StartOutcome start(HttpServletRequest request, String returnTo) {
        if (!google.enabled()) {
            return new Unavailable();
        }
        Instant now = clock.instant();
        String clientKey = AuthKeys.sha256Hex(String.valueOf(request.getRemoteAddr()));
        if (properties.googleStartsPerClientLimit().reached(attempts.countByClient(Kind.GOOGLE_INICIADO, clientKey,
                properties.googleStartsPerClientLimit().since(now)))) {
            return new Throttled();
        }
        attempts.record(Kind.GOOGLE_INICIADO, null, clientKey, now);
        String state = AuthKeys.newToken();
        String nonce = AuthKeys.newToken();
        String verifier = AuthKeys.newToken();
        session.beginOAuth(request, new OAuthRequest(state, nonce, verifier, safeReturnPath(returnTo), now),
                properties.oauthTtl());
        return new RedirectToGoogle(google.authorizationUri(state, nonce, AuthKeys.pkceChallenge(verifier)));
    }

    /**
     * Retorno do Google. O pedido guardado sai da sessão já na entrada, então cada state vale uma vez, mesmo que o
     * retorno falhe.
     */
    public CallbackOutcome callback(HttpServletRequest request, String state, String code, String error) {
        Optional<OAuthRequest> pending = session.takeOAuth(request);
        Instant now = clock.instant();
        if (pending.isEmpty() || state == null || !constantTimeEquals(pending.get().state(), state)
                || !now.isBefore(pending.get().since().plus(properties.oauthTtl()))) {
            log.info("Retorno do Google recusado: pedido ausente, vencido ou state diferente");
            return new Failed(pending.map(OAuthRequest::returnTo).orElse(DEFAULT_RETURN));
        }
        String returnTo = pending.get().returnTo();
        if (error != null) {
            return new Denied(returnTo);
        }
        if (code == null || code.isBlank()) {
            return new Failed(returnTo);
        }
        Optional<GoogleIdentity> identity = google.exchange(code, pending.get().verifier(), pending.get().nonce());
        if (identity.isEmpty()) {
            log.info("Retorno do Google recusado: código ou ID token inválido");
            return new Failed(returnTo);
        }
        GoogleIdentity found = identity.get();
        String email = AuthKeys.normalizeEmail(found.email());
        if (!AuthKeys.plausibleEmail(email)) {
            return new Failed(returnTo);
        }
        Optional<UUID> existing = tenants.forGoogleSubject(found.subject(), () -> {
            Optional<UUID> id = accounts.idBySubject(found.subject());
            id.ifPresent(account -> accounts.touchLogin(account, email, found.emailVerified(), now));
            return id;
        });
        if (existing.isPresent()) {
            session.signIn(request, existing.get(), now);
            log.info("Consumidor {} entrou com Google", existing.get());
            return new SignedIn(returnTo);
        }
        session.holdSignup(request, new PendingSignup(found.subject(), email, found.emailVerified(), returnTo, now));
        return new PrivacyNoticeRequired(returnTo);
    }

    /** E-mail da identidade Google aguardando o aviso de privacidade, para a tela mostrar. */
    public Optional<String> pendingEmail(HttpServletRequest request) {
        return pendingSignup(request).map(PendingSignup::email);
    }

    /** Aceitou o aviso de privacidade: cria a conta e abre a sessão do consumidor. */
    public AcceptOutcome accept(HttpServletRequest request, String noticeVersion) {
        Optional<PendingSignup> pending = pendingSignup(request);
        if (pending.isEmpty()) {
            return new NothingPending();
        }
        if (!properties.privacyNoticeVersion().equals(noticeVersion)) {
            return new OutdatedNotice(properties.privacyNoticeVersion());
        }
        PendingSignup signup = pending.get();
        Instant now = clock.instant();
        UUID accountId;
        try {
            accountId = tenants.forGoogleSubject(signup.subject(), () -> accounts.idBySubject(signup.subject())
                    .orElseGet(() -> {
                        UUID id = UUID.randomUUID();
                        accounts.insert(id, signup.subject(), signup.email(), signup.emailVerified(),
                                properties.privacyNoticeVersion(), now);
                        return id;
                    }));
        } catch (DuplicateKeyException concurrent) {
            // Duas abas aceitaram ao mesmo tempo: a conta já existe, entra nela.
            accountId = tenants.forGoogleSubject(signup.subject(), () -> accounts.idBySubject(signup.subject()))
                    .orElseThrow();
        }
        session.clearSignup(request);
        session.signIn(request, accountId, now);
        log.info("Conta de consumidor {} criada com o aviso de privacidade {}", accountId,
                properties.privacyNoticeVersion());
        return new AccountCreated(signup.returnTo());
    }

    /** Recusou o aviso: nada é guardado e a pessoa continua como avulsa. */
    public void decline(HttpServletRequest request) {
        session.clearSignup(request);
    }

    public Optional<ConsumerAccount> account(UUID accountId) {
        return tenants.asConsumer(accountId, () -> accounts.account(accountId))
                .map(row -> new ConsumerAccount(row.id(), row.email(), row.createdAt()));
    }

    public String privacyNoticeVersion() {
        return properties.privacyNoticeVersion();
    }

    private Optional<PendingSignup> pendingSignup(HttpServletRequest request) {
        Optional<PendingSignup> pending = session.signup(request);
        if (pending.isPresent() && !clock.instant().isBefore(pending.get().since().plus(properties.oauthTtl()))) {
            session.clearSignup(request);
            return Optional.empty();
        }
        return pending;
    }

    /** Só caminhos do próprio app, das telas do consumidor; qualquer outra coisa volta para "Minhas comandas". */
    public static String safeReturnPath(String returnTo) {
        if (returnTo == null || !returnTo.matches("/(minhas-comandas|minha-comanda/[0-9a-fA-F-]{36}|meu-historico)")) {
            return DEFAULT_RETURN;
        }
        return returnTo;
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
