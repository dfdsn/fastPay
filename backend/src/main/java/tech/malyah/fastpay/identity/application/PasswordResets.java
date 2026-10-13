package tech.malyah.fastpay.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.domain.PasswordPolicy;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore.Kind;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore.Account;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore.ResetToken;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Redefinição de senha por e-mail (H08, FR-04, especificação §5): link de 30 minutos e uso único; um
 * pedido novo invalida o link anterior; concluir troca a senha e encerra todas as sessões da pessoa.
 * MFA (H09) fica intacto. O pedido tem sempre a mesma resposta, exista a conta ou não.
 */
@Service
public class PasswordResets {

    public static final Duration LINK_VALIDITY = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(PasswordResets.class);

    public enum RequestOutcome {
        ACCEPTED,
        THROTTLED
    }

    public sealed interface ConfirmOutcome {
    }

    public record PasswordChanged(UUID identityId, int sessionsEnded) implements ConfirmOutcome {
    }

    public record InvalidLink() implements ConfirmOutcome {
    }

    public record WeakPassword(String reason) implements ConfirmOutcome {
    }

    public record ConfirmThrottled() implements ConfirmOutcome {
    }

    private final JdbcCredentialStore credentials;
    private final JdbcAuthAttemptStore attempts;
    private final TenantTransactions tenants;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AccountMailer mailer;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final AuthProperties properties;
    private final Clock clock;

    PasswordResets(JdbcCredentialStore credentials, JdbcAuthAttemptStore attempts, TenantTransactions tenants,
            PasswordEncoder encoder, PasswordPolicy policy, AccountMailer mailer,
            FindByIndexNameSessionRepository<?> sessions, AuthProperties properties, Clock clock) {
        this.credentials = credentials;
        this.attempts = attempts;
        this.tenants = tenants;
        this.encoder = encoder;
        this.policy = policy;
        this.mailer = mailer;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    public RequestOutcome request(String email, String clientAddress) {
        Instant now = clock.instant();
        String normalized = AuthKeys.normalizeEmail(email);
        String subjectKey = AuthKeys.sha256Hex(normalized);
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (properties.resetRequestPerClient().reached(attempts.countByClient(Kind.REDEFINICAO_PEDIDA, clientKey,
                properties.resetRequestPerClient().since(now)))) {
            return RequestOutcome.THROTTLED;
        }
        boolean accountLimitReached = properties.resetRequestPerAccount().reached(attempts.countBySubject(
                Kind.REDEFINICAO_PEDIDA, subjectKey, properties.resetRequestPerAccount().since(now)));
        attempts.record(Kind.REDEFINICAO_PEDIDA, subjectKey, clientKey, now);
        if (accountLimitReached || normalized.isEmpty()) {
            return RequestOutcome.ACCEPTED;
        }

        Optional<Account> account = tenants.forLoginEmail(normalized, () -> credentials.accountByEmail(normalized));
        if (account.isEmpty()) {
            return RequestOutcome.ACCEPTED;
        }
        UUID identityId = account.get().id();
        String token = AuthKeys.newToken();
        Instant expiresAt = now.plus(LINK_VALIDITY);
        try {
            tenants.asIdentity(identityId, () -> {
                credentials.invalidateOpenResetTokens(identityId, now);
                credentials.insertResetToken(identityId, AuthKeys.sha256Hex(token), now, expiresAt);
                return null;
            });
        } catch (DuplicateKeyException concurrent) {
            // Outro pedido simultâneo da mesma conta criou o link em aberto; ele é o que vale.
            return RequestOutcome.ACCEPTED;
        }
        mailer.sendPasswordReset(account.get().email(), account.get().displayName(),
                properties.resetLinkBase() + "#token=" + token, expiresAt);
        log.info("Link de redefinição de senha criado para a identidade {}", identityId);
        return RequestOutcome.ACCEPTED;
    }

    public ConfirmOutcome confirm(String token, String newPassword, String clientAddress) {
        Instant now = clock.instant();
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (properties.resetFailurePerClient().reached(attempts.countByClient(Kind.REDEFINICAO_FALHOU, clientKey,
                properties.resetFailurePerClient().since(now)))) {
            return new ConfirmThrottled();
        }
        if (token == null || token.isBlank() || token.length() > 100) {
            return invalidLink(clientKey, now);
        }
        String tokenHash = AuthKeys.sha256Hex(token);
        Optional<ResetToken> presented = tenants.forResetToken(tokenHash, () -> credentials.resetToken(tokenHash));
        if (presented.isEmpty() || !presented.get().open() || !now.isBefore(presented.get().expiresAt())) {
            return invalidLink(clientKey, now);
        }

        UUID identityId = presented.get().identityId();
        String email = tenants.asIdentity(identityId, () -> credentials.account(identityId))
                .map(Account::email).orElse(null);
        Optional<String> weak = policy.violation(newPassword, email);
        if (weak.isPresent()) {
            // O link continua valendo: a pessoa tenta outra senha.
            return new WeakPassword(weak.get());
        }
        String hash = encoder.encode(newPassword);
        boolean changed = tenants.asIdentity(identityId, () -> {
            if (!credentials.consumeResetToken(identityId, tokenHash, now)) {
                return false;
            }
            credentials.savePasswordHash(identityId, hash, now);
            return true;
        });
        if (!changed) {
            return invalidLink(clientKey, now);
        }

        int ended = endSessions(identityId);
        log.info("Senha redefinida para a identidade {}; {} sessões encerradas", identityId, ended);
        return new PasswordChanged(identityId, ended);
    }

    /** Encerra todas as sessões da identidade (principal da sessão = id da identidade). */
    private int endSessions(UUID identityId) {
        var ids = sessions.findByPrincipalName(identityId.toString()).keySet();
        ids.forEach(sessions::deleteById);
        return ids.size();
    }

    private InvalidLink invalidLink(String clientKey, Instant now) {
        attempts.record(Kind.REDEFINICAO_FALHOU, null, clientKey, now);
        return new InvalidLink();
    }
}
