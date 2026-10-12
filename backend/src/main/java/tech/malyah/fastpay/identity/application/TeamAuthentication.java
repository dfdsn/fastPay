package tech.malyah.fastpay.identity.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.domain.PasswordPolicy;
import tech.malyah.fastpay.identity.domain.SessionProfile;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore.Kind;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore.Account;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Login da equipe por e-mail e senha (H08, FR-04). Conta inexistente, sem senha ou senha errada têm a
 * mesma resposta e custo parecido (o hash é sempre verificado). Falhas são limitadas por conta e por
 * origem antes de a senha ser conferida.
 */
@Service
public class TeamAuthentication {

    private static final Logger log = LoggerFactory.getLogger(TeamAuthentication.class);

    public sealed interface LoginOutcome {
    }

    public record Authenticated(UUID identityId, String displayName, SessionProfile profile) implements LoginOutcome {
    }

    public record Rejected() implements LoginOutcome {
    }

    public record Throttled() implements LoginOutcome {
    }

    private final JdbcCredentialStore credentials;
    private final JdbcAuthAttemptStore attempts;
    private final TenantTransactions tenants;
    private final PasswordEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String decoyHash;

    TeamAuthentication(JdbcCredentialStore credentials, JdbcAuthAttemptStore attempts, TenantTransactions tenants,
            PasswordEncoder encoder, AuthProperties properties, Clock clock) {
        this.credentials = credentials;
        this.attempts = attempts;
        this.tenants = tenants;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.decoyHash = encoder.encode(AuthKeys.newToken());
    }

    public LoginOutcome login(String email, String password, String clientAddress) {
        Instant now = clock.instant();
        String normalized = AuthKeys.normalizeEmail(email);
        String subjectKey = AuthKeys.sha256Hex(normalized);
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (properties.loginPerAccount().reached(attempts.countBySubject(Kind.LOGIN_FALHOU, subjectKey,
                properties.loginPerAccount().since(now)))
                || properties.loginPerClient().reached(attempts.countByClient(Kind.LOGIN_FALHOU, clientKey,
                        properties.loginPerClient().since(now)))) {
            return new Throttled();
        }

        Optional<Account> account = normalized.isEmpty() ? Optional.empty()
                : tenants.forLoginEmail(normalized, () -> credentials.accountByEmail(normalized));
        Optional<String> hash = account.flatMap(a -> tenants.asIdentity(a.id(), () -> credentials.passwordHash(a.id())));
        if (!passwordMatches(password, hash)) {
            attempts.record(Kind.LOGIN_FALHOU, subjectKey, clientKey, now);
            return new Rejected();
        }

        UUID identityId = account.get().id();
        attempts.clearSubject(Kind.LOGIN_FALHOU, subjectKey);
        SessionProfile profile = tenants.asIdentity(identityId, () -> SessionProfile.mostRestrictive(
                credentials.isPlatformAdmin(identityId), credentials.activeRoles(identityId)));
        log.info("Login aceito para a identidade {} com perfil de sessão {}", identityId, profile);
        return new Authenticated(identityId, account.get().displayName(), profile);
    }

    /** Sempre roda o hash, com o da conta ou um falso, para não revelar se a conta existe pelo tempo. */
    private boolean passwordMatches(String password, Optional<String> hash) {
        boolean usable = password != null && !password.isEmpty()
                && password.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES;
        boolean matches = encoder.matches(usable ? password : "x", hash.orElse(decoyHash));
        return usable && hash.isPresent() && matches;
    }
}
