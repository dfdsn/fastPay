package tech.malyah.fastpay.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.PlatformAccess;
import tech.malyah.fastpay.platform.infrastructure.JdbcSupportGrantStore;
import tech.malyah.fastpay.platform.infrastructure.JdbcSupportGrantStore.Grant;
import tech.malyah.fastpay.platform.infrastructure.SupportProperties;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Suporte autorizado (H11, FR-66, CA-28). O dono (administrador do estabelecimento) concede a uma pessoa da equipe
 * fastPay (admin fastPay) acesso temporário de leitura à unidade, com motivo e prazo, e pode encerrá-lo antes. O
 * suporte não ganha vínculo, unidade na sessão nem identidade de funcionário: cada consulta confere a concessão
 * vigente, fica na auditoria da unidade com a autoria do próprio suporte e roda numa transação somente leitura do
 * PostgreSQL, de modo que nenhuma escrita (desconto, cancelamento, devolução...) é possível por este caminho.
 */
@Service
public class SupportAccess {

    private static final Logger log = LoggerFactory.getLogger(SupportAccess.class);

    public enum Status {
        ATIVA,
        VENCIDA,
        ENCERRADA
    }

    public record GrantView(UUID id, UUID establishmentId, String establishmentName, String supportEmail,
            String reason, Instant grantedAt, Instant expiresAt, Instant revokedAt, Status status, long consultations,
            Instant lastConsultationAt) {
    }

    public enum Refusal {
        /** O e-mail não é de uma pessoa da equipe fastPay. */
        NAO_E_SUPORTE,
        /** Já existe concessão vigente para esta pessoa nesta unidade. */
        JA_AUTORIZADO,
        MOTIVO_INVALIDO,
        PRAZO_INVALIDO,
        CONCESSAO_NAO_ENCONTRADA
    }

    public sealed interface Outcome {
    }

    public record Done(List<GrantView> grants) implements Outcome {
    }

    public record Refused(Refusal refusal) implements Outcome {
    }

    private final Memberships memberships;
    private final PlatformAccess platform;
    private final TenantTransactions tenants;
    private final JdbcSupportGrantStore store;
    private final SupportProperties properties;
    private final Clock clock;

    SupportAccess(Memberships memberships, PlatformAccess platform, TenantTransactions tenants,
            JdbcSupportGrantStore store, SupportProperties properties, Clock clock) {
        this.memberships = memberships;
        this.platform = platform;
        this.tenants = tenants;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Duration maxDuration() {
        return properties.maxDuration();
    }

    /** Concessões da unidade, para o dono. */
    public List<GrantView> grants(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.AUTORIZAR_SUPORTE, me -> views());
    }

    /** Concede leitura temporária à pessoa de suporte deste e-mail. */
    public Outcome grant(UUID actorId, UUID establishmentId, String supportEmail, String reason, Duration duration,
            String correlationId) {
        String motive = reason == null ? "" : reason.strip();
        if (motive.length() < 5 || motive.length() > 500) {
            return new Refused(Refusal.MOTIVO_INVALIDO);
        }
        if (duration == null || duration.toMinutes() < 60 || duration.compareTo(properties.maxDuration()) > 0) {
            return new Refused(Refusal.PRAZO_INVALIDO);
        }
        String email = supportEmail == null ? "" : supportEmail.strip().toLowerCase(Locale.ROOT);
        return memberships.inUnit(actorId, establishmentId, Permission.AUTORIZAR_SUPORTE, me -> {
            UUID supportId = email.isEmpty() ? null : store.supportIdentityByEmail(email).orElse(null);
            if (supportId == null) {
                return new Refused(Refusal.NAO_E_SUPORTE);
            }
            Instant now = clock.instant();
            if (store.activeFor(supportId, now)) {
                return new Refused(Refusal.JA_AUTORIZADO);
            }
            UUID id = UUID.randomUUID();
            store.insert(id, supportId, email, motive, actorId, now, now.plus(duration));
            store.audit("suporte.concedido", id, actorId, duration.toHours() + "h", correlationId);
            log.info("Suporte {} autorizado na unidade {} por {} até {}", supportId, establishmentId, actorId,
                    now.plus(duration));
            return new Done(views());
        });
    }

    /** O dono encerra a concessão antes do prazo. */
    public Outcome revoke(UUID actorId, UUID establishmentId, UUID grantId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.AUTORIZAR_SUPORTE, me -> {
            if (!store.revoke(grantId, actorId, clock.instant())) {
                return new Refused(Refusal.CONCESSAO_NAO_ENCONTRADA);
            }
            store.audit("suporte.encerrado", grantId, actorId, null, correlationId);
            return new Done(views());
        });
    }

    /** Concessões vigentes dadas à pessoa de suporte, em todas as unidades. */
    public List<GrantView> activeGrantsOf(UUID supportIdentityId) {
        Instant now = clock.instant();
        return platform.asPlatformAdmin(supportIdentityId,
                () -> store.activeForCurrentIdentity(now).stream().map(g -> view(g, now)).toList());
    }

    /**
     * Consulta de suporte: confere a concessão vigente, registra na auditoria da unidade o que foi consultado
     * ({@code what}, sem dado pessoal) e executa {@code query} numa transação somente leitura com o contexto da
     * unidade, conferindo a concessão de novo dentro dela.
     *
     * @throws SupportNotAuthorizedException sem concessão vigente, unidade inexistente ou suporte que deixou de
     *                                       ser admin fastPay
     */
    public <T> T consult(UUID supportIdentityId, UUID establishmentId, String what, Supplier<T> query) {
        Objects.requireNonNull(supportIdentityId, "supportIdentityId");
        Objects.requireNonNull(establishmentId, "establishmentId");
        Objects.requireNonNull(query, "query");
        Instant now = clock.instant();
        String correlationId = UUID.randomUUID().toString();
        tenants.inEstablishmentAs(establishmentId, supportIdentityId, () -> {
            UUID grantId = store.activeGrantForCurrentIdentity(now).orElseThrow(() -> refused(supportIdentityId,
                    establishmentId));
            store.audit("suporte.consultado", grantId, supportIdentityId, what, correlationId);
            return grantId;
        });
        return tenants.readOnlyInEstablishmentAs(establishmentId, supportIdentityId, () -> {
            store.activeGrantForCurrentIdentity(now).orElseThrow(() -> refused(supportIdentityId, establishmentId));
            return query.get();
        });
    }

    private List<GrantView> views() {
        Instant now = clock.instant();
        return store.inCurrentUnit().stream().map(g -> view(g, now)).toList();
    }

    private static GrantView view(Grant g, Instant now) {
        Status status = g.revokedAt() != null ? Status.ENCERRADA : g.activeAt(now) ? Status.ATIVA : Status.VENCIDA;
        return new GrantView(g.id(), g.establishmentId(), g.establishmentName(), g.supportEmail(), g.reason(),
                g.grantedAt(), g.expiresAt(), g.revokedAt(), status, g.consultations(), g.lastConsultationAt());
    }

    private static SupportNotAuthorizedException refused(UUID supportIdentityId, UUID establishmentId) {
        log.warn("Consulta de suporte recusada: identidade {} sem concessão vigente na unidade {}", supportIdentityId,
                establishmentId);
        return new SupportNotAuthorizedException();
    }
}
