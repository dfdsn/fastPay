package tech.malyah.fastpay.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.domain.AuthStage;
import tech.malyah.fastpay.identity.domain.Base32;
import tech.malyah.fastpay.identity.domain.RecoveryCodes;
import tech.malyah.fastpay.identity.domain.SessionProfile;
import tech.malyah.fastpay.identity.domain.Totp;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore.Kind;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore.Account;
import tech.malyah.fastpay.identity.infrastructure.JdbcMfaStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcMfaStore.Authenticator;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.jobs.JobRequest;
import tech.malyah.fastpay.jobs.Jobs;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Verificação em duas etapas da equipe (H09, FR-05, especificação §5): TOTP no aplicativo autenticador e
 * dez códigos de recuperação de uso único. Obrigatória para admin fastPay, admin do estabelecimento e
 * gerente; opcional para garçom e produção. Código de recuperação não entra direto: abre só o recadastro
 * do autenticador, que invalida o autenticador e os códigos anteriores, encerra todas as sessões da
 * pessoa e manda um aviso. Redefinir a senha não toca em nada disto.
 */
@Service
public class MultiFactor {

    private static final Logger log = LoggerFactory.getLogger(MultiFactor.class);
    static final String SELF = "propria-identidade";

    /**
     * Situação do MFA da identidade agora (papéis e vínculos podem ter mudado desde o login) e o perfil de
     * sessão que vale para ela.
     */
    public record Requirement(boolean enrolled, boolean required, SessionProfile profile) {
    }

    /**
     * Cadastro iniciado: o segredo em claro vai só para a tela da pessoa; a sessão guarda a versão
     * cifrada até a confirmação com um código.
     */
    public record Enrollment(String secretCiphertext, String secret, String provisioningUri) {
    }

    public sealed interface VerifyOutcome {
    }

    public record TotpAccepted() implements VerifyOutcome {
    }

    public record RecoveryCodeAccepted() implements VerifyOutcome {
    }

    public record CodeRejected() implements VerifyOutcome {
    }

    public record CodeThrottled() implements VerifyOutcome {
    }

    public sealed interface EnrollOutcome {
    }

    /** Códigos em claro, para mostrar uma única vez. */
    public record Enrolled(List<String> recoveryCodes, boolean replaced, int sessionsEnded) implements EnrollOutcome {
    }

    public record EnrollCodeRejected() implements EnrollOutcome {
    }

    public record EnrollThrottled() implements EnrollOutcome {
    }

    private final JdbcMfaStore store;
    private final JdbcCredentialStore credentials;
    private final JdbcAuthAttemptStore attempts;
    private final TenantTransactions tenants;
    private final MfaSecretCipher cipher;
    private final Jobs jobs;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final AuthProperties properties;
    private final Clock clock;

    MultiFactor(JdbcMfaStore store, JdbcCredentialStore credentials, JdbcAuthAttemptStore attempts,
            TenantTransactions tenants, MfaSecretCipher cipher, Jobs jobs,
            FindByIndexNameSessionRepository<?> sessions, AuthProperties properties, Clock clock) {
        this.store = store;
        this.credentials = credentials;
        this.attempts = attempts;
        this.tenants = tenants;
        this.cipher = cipher;
        this.jobs = jobs;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    public Requirement requirement(UUID identityId) {
        return tenants.asIdentity(identityId, () -> {
            boolean platformAdmin = credentials.isPlatformAdmin(identityId);
            var roles = credentials.activeRoles(identityId);
            return new Requirement(store.authenticator(identityId).isPresent(),
                    AuthStage.mfaRequired(platformAdmin, roles), SessionProfile.mostRestrictive(platformAdmin, roles));
        });
    }

    public Enrollment startEnrollment(UUID identityId) {
        String email = tenants.asIdentity(identityId, () -> credentials.account(identityId))
                .map(Account::email).orElseThrow(() -> new IllegalStateException("Identidade inexistente"));
        byte[] secret = Totp.newSecret();
        return new Enrollment(cipher.encrypt(identityId, secret), group(Base32.encode(secret)),
                Totp.provisioningUri(properties.mfaIssuer(), email, secret));
    }

    /**
     * Segundo passo do login: código do autenticador ou de recuperação. Erros contam no limite por
     * identidade (T-03), separado do limite da senha.
     */
    public VerifyOutcome verify(UUID identityId, String code, String clientAddress) {
        Instant now = clock.instant();
        String subjectKey = subjectKey(identityId);
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (throttled(subjectKey, now)) {
            return new CodeThrottled();
        }
        if (Totp.normalize(code) != null) {
            boolean accepted = tenants.asIdentity(identityId, () -> {
                Optional<Authenticator> authenticator = store.authenticator(identityId);
                if (authenticator.isEmpty()) {
                    return false;
                }
                byte[] secret = cipher.decrypt(identityId, authenticator.get().secretCiphertext());
                OptionalLong step = Totp.matchingStep(secret, code, now, authenticator.get().lastUsedStep());
                return step.isPresent() && store.advanceStep(identityId, step.getAsLong());
            });
            if (accepted) {
                attempts.clearSubject(Kind.MFA_FALHOU, subjectKey);
                return new TotpAccepted();
            }
        } else {
            Optional<String> recovery = RecoveryCodes.normalize(code);
            boolean consumed = recovery.isPresent() && tenants.asIdentity(identityId, () -> {
                if (!store.consumeRecoveryCode(identityId, RecoveryCodes.hash(identityId, recovery.get()), now)) {
                    return false;
                }
                store.securityEvent(identityId, "mfa.codigo_recuperacao_usado", SELF, null, now);
                return true;
            });
            if (consumed) {
                attempts.clearSubject(Kind.MFA_FALHOU, subjectKey);
                log.info("Código de recuperação usado pela identidade {}; recadastro do autenticador exigido", identityId);
                return new RecoveryCodeAccepted();
            }
        }
        attempts.record(Kind.MFA_FALHOU, subjectKey, clientKey, now);
        return new CodeRejected();
    }

    /**
     * Confirma o cadastro com um código do autenticador novo. Grava o segredo, troca os códigos de
     * recuperação e registra na trilha. Se já havia autenticador (recadastro), encerra todas as sessões da
     * pessoa e manda o aviso; a sessão de quem chama também é encerrada e quem chama cria outra.
     */
    public EnrollOutcome completeEnrollment(UUID identityId, String secretCiphertext, String code,
            String clientAddress) {
        Instant now = clock.instant();
        String subjectKey = subjectKey(identityId);
        if (throttled(subjectKey, now)) {
            return new EnrollThrottled();
        }
        byte[] secret = cipher.decrypt(identityId, secretCiphertext);
        OptionalLong step = Totp.matchingStep(secret, code, now, -1);
        if (step.isEmpty()) {
            attempts.record(Kind.MFA_FALHOU, subjectKey, AuthKeys.sha256Hex(String.valueOf(clientAddress)), now);
            return new EnrollCodeRejected();
        }
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = codes.stream()
                .map(c -> RecoveryCodes.hash(identityId, RecoveryCodes.normalize(c).orElseThrow()))
                .toList();
        boolean replaced = tenants.asIdentity(identityId, () -> {
            boolean previous = store.saveAuthenticator(identityId, secretCiphertext, step.getAsLong(), now);
            store.replaceRecoveryCodes(identityId, hashes, now);
            store.securityEvent(identityId, previous ? "mfa.recadastrado" : "mfa.cadastrado", SELF, null, now);
            if (previous) {
                // Aviso durável na mesma transação do recadastro (H12).
                jobs.enqueue(new JobRequest(MfaReplacedEmailJob.KIND, now.toString()));
            }
            return previous;
        });
        attempts.clearSubject(Kind.MFA_FALHOU, subjectKey);
        int ended = 0;
        if (replaced) {
            ended = endSessions(sessions, identityId);
        }
        log.info("Autenticador {} para a identidade {}; {} sessões encerradas", replaced ? "trocado" : "cadastrado",
                identityId, ended);
        return new Enrolled(codes, replaced, ended);
    }

    /** Encerra todas as sessões da identidade (principal da sessão = id da identidade). */
    static int endSessions(FindByIndexNameSessionRepository<?> sessions, UUID identityId) {
        var ids = sessions.findByPrincipalName(identityId.toString()).keySet();
        ids.forEach(sessions::deleteById);
        return ids.size();
    }

    private boolean throttled(String subjectKey, Instant now) {
        return properties.mfaPerAccount().reached(attempts.countBySubject(Kind.MFA_FALHOU, subjectKey,
                properties.mfaPerAccount().since(now)));
    }

    private static String subjectKey(UUID identityId) {
        return AuthKeys.sha256Hex("mfa:" + identityId);
    }

    /** Grupos de quatro, para a pessoa digitar no aplicativo sem se perder. */
    private static String group(String secret) {
        return String.join(" ", secret.split("(?<=\\G.{4})"));
    }
}
