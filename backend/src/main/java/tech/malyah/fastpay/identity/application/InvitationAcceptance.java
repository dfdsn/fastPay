package tech.malyah.fastpay.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.domain.PasswordPolicy;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcAuthAttemptStore.Kind;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore.Invitation;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Aceite de convite (H10, FR-02; especificação §5). Quem não tem conta cria a sua com nome e senha no próprio
 * aceite: identidade, senha e vínculo nascem juntos, na transação que consome o convite. Quem já tem conta com o
 * e-mail do convite precisa entrar com ela (inclusive o MFA, quando exigido) e aceitar autenticado. Convite
 * vencido, usado, substituído ou inexistente tem sempre a mesma resposta; essas falhas contam por origem, com o
 * mesmo limite dos links de redefinição de senha (T-03).
 */
@Service
public class InvitationAcceptance {

    private static final Logger log = LoggerFactory.getLogger(InvitationAcceptance.class);

    /** Convite válido, como a tela de aceite mostra. {@code accountExists}: o e-mail já tem conta no fastPay. */
    public record Preview(String establishmentName, MembershipRole role, String email, Instant expiresAt,
            boolean accountExists) {
    }

    public sealed interface PreviewOutcome {
    }

    public record Valid(Preview preview) implements PreviewOutcome {
    }

    public sealed interface AcceptOutcome {
    }

    public record Accepted(UUID identityId, UUID establishmentId, UUID membershipId) implements AcceptOutcome {
    }

    /** O e-mail do convite já tem conta: entrar com ela e aceitar autenticado. */
    public record SignInRequired() implements AcceptOutcome {
    }

    /** A sessão é de outra conta, com e-mail diferente do convite. */
    public record OtherAccount() implements AcceptOutcome {
    }

    /** A pessoa já participa da unidade. O convite continua em aberto. */
    public record AlreadyMember() implements AcceptOutcome {
    }

    public record InvalidData(String reason) implements AcceptOutcome {
    }

    public record InvalidInvitation() implements PreviewOutcome, AcceptOutcome {
    }

    public record Throttled() implements PreviewOutcome, AcceptOutcome {
    }

    private final JdbcInvitationStore invitations;
    private final JdbcCredentialStore credentials;
    private final JdbcAuthAttemptStore attempts;
    private final TenantTransactions tenants;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuthProperties properties;
    private final Clock clock;

    InvitationAcceptance(JdbcInvitationStore invitations, JdbcCredentialStore credentials,
            JdbcAuthAttemptStore attempts, TenantTransactions tenants, PasswordEncoder encoder, PasswordPolicy policy,
            AuthProperties properties, Clock clock) {
        this.invitations = invitations;
        this.credentials = credentials;
        this.attempts = attempts;
        this.tenants = tenants;
        this.encoder = encoder;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    public PreviewOutcome preview(String token, String clientAddress) {
        Instant now = clock.instant();
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (throttled(clientKey, now)) {
            return new Throttled();
        }
        Optional<Invitation> invitation = acceptable(token, now);
        if (invitation.isEmpty()) {
            return invalid(clientKey, now);
        }
        Invitation i = invitation.get();
        return new Valid(new Preview(i.establishmentName(), i.role(), i.email(), i.expiresAt(), accountExists(i.email())));
    }

    /** Aceite sem sessão: cria a conta do e-mail do convite. Se o e-mail já tem conta, pede para entrar. */
    public AcceptOutcome acceptWithNewAccount(String token, String displayName, String password, String clientAddress,
            String correlationId) {
        Instant now = clock.instant();
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (throttled(clientKey, now)) {
            return new Throttled();
        }
        Optional<Invitation> invitation = acceptable(token, now);
        if (invitation.isEmpty()) {
            return invalid(clientKey, now);
        }
        Invitation i = invitation.get();
        if (accountExists(i.email())) {
            return new SignInRequired();
        }
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.length() > 120) {
            return new InvalidData("Informe o seu nome, com até 120 caracteres.");
        }
        Optional<String> weak = policy.violation(password, i.email());
        if (weak.isPresent()) {
            return new InvalidData(weak.get());
        }
        String hash = encoder.encode(password);
        String tokenHash = AuthKeys.sha256Hex(token);
        UUID identityId = UUID.randomUUID();
        try {
            return tenants.forInvitationToken(tokenHash, identityId, () -> {
                if (!invitations.lockAcceptable(tokenHash, now)) {
                    throw new InvitationTaken();
                }
                invitations.insertIdentity(identityId, i.email(), name);
                credentials.savePasswordHash(identityId, hash, now);
                return consume(tokenHash, i, identityId, now, correlationId).orElseThrow(InvitationTaken::new);
            });
        } catch (InvitationTaken taken) {
            return invalid(clientKey, now);
        } catch (DuplicateKeyException sameEmailMeanwhile) {
            // Outra aba ou outro convite criou a conta deste e-mail ao mesmo tempo.
            return new SignInRequired();
        }
    }

    /** Aceite com a conta da sessão, que precisa ter o e-mail do convite. */
    public AcceptOutcome acceptAsSignedIn(String token, UUID identityId, String clientAddress, String correlationId) {
        Instant now = clock.instant();
        String clientKey = AuthKeys.sha256Hex(String.valueOf(clientAddress));
        if (throttled(clientKey, now)) {
            return new Throttled();
        }
        Optional<Invitation> invitation = acceptable(token, now);
        if (invitation.isEmpty()) {
            return invalid(clientKey, now);
        }
        Invitation i = invitation.get();
        String tokenHash = AuthKeys.sha256Hex(token);
        try {
            return tenants.forInvitationToken(tokenHash, identityId, () -> {
                if (!invitations.lockAcceptable(tokenHash, now)) {
                    throw new InvitationTaken();
                }
                if (!invitations.currentIdentityEmail(identityId).map(i.email()::equals).orElse(false)) {
                    return new OtherAccount();
                }
                if (invitations.activeMembership(identityId, i.establishmentId())) {
                    return new AlreadyMember();
                }
                return consume(tokenHash, i, identityId, now, correlationId).<AcceptOutcome>map(a -> a)
                        .orElseThrow(InvitationTaken::new);
            });
        } catch (InvitationTaken taken) {
            return invalid(clientKey, now);
        }
    }

    /** Marca o convite como aceito (uma vez só), cria o vínculo e audita na unidade. */
    private Optional<Accepted> consume(String tokenHash, Invitation i, UUID identityId, Instant now,
            String correlationId) {
        if (!invitations.accept(tokenHash, identityId, now)) {
            return Optional.empty();
        }
        UUID membershipId = invitations.insertMembership(identityId, i.establishmentId(), i.role());
        invitations.audit(i.establishmentId(), "convite.aceito", membershipId, identityId, correlationId);
        log.info("Convite {} aceito pela identidade {}: vínculo {} como {}", i.id(), identityId, membershipId, i.role());
        return Optional.of(new Accepted(identityId, i.establishmentId(), membershipId));
    }

    private Optional<Invitation> acceptable(String token, Instant now) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        String tokenHash = AuthKeys.sha256Hex(token);
        return tenants.forInvitationToken(tokenHash, null, () -> invitations.presented(tokenHash))
                .filter(i -> i.acceptableAt(now));
    }

    private boolean accountExists(String email) {
        return tenants.forLoginEmail(email, () -> credentials.accountByEmail(email)).isPresent();
    }

    private boolean throttled(String clientKey, Instant now) {
        return properties.resetFailurePerClient().reached(attempts.countByClient(Kind.CONVITE_FALHOU, clientKey,
                properties.resetFailurePerClient().since(now)));
    }

    private InvalidInvitation invalid(String clientKey, Instant now) {
        attempts.record(Kind.CONVITE_FALHOU, null, clientKey, now);
        return new InvalidInvitation();
    }

    /** O convite foi aceito ou substituído entre a leitura e o aceite: desfaz a transação. */
    private static final class InvitationTaken extends RuntimeException {
        InvitationTaken() {
            super(null, null, false, false);
        }
    }
}
