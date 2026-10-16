package tech.malyah.fastpay.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore.Invitation;
import tech.malyah.fastpay.jobs.JobRequest;
import tech.malyah.fastpay.jobs.Jobs;

/**
 * Convites para participar de uma unidade (H10, FR-02; especificação §5). O convite vale 48 h a partir do
 * envio do e-mail e só uma vez; convidar de novo o mesmo e-mail na unidade invalida o convite anterior. O
 * e-mail sai pela fila de jobs da unidade; o token só existe no envio. Aceite em
 * {@code identity.application.InvitationAcceptance}.
 */
@Service
public class Invitations {

    public static final Duration VALIDITY = Duration.ofHours(48);

    /** Job do e-mail do convite, no escopo da unidade; o executor fica em {@code identity.application}. */
    public static final String EMAIL_JOB_KIND = "identity.email_convite";

    /** Situação do convite para quem convidou. */
    public enum Status {
        /** Gravado; o e-mail ainda não saiu (fila, nova tentativa ou fila de falhas). */
        ENVIO_PENDENTE,
        ENVIADO,
        VENCIDO,
        ACEITO,
        /** Substituído por um convite novo. */
        INVALIDADO
    }

    public record Summary(UUID id, String email, MembershipRole role, Status status, Instant createdAt, Instant sentAt,
            Instant expiresAt, Instant acceptedAt) {
    }

    private final JdbcInvitationStore store;
    private final Jobs jobs;
    private final Clock clock;

    Invitations(JdbcInvitationStore store, Jobs jobs, Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * Grava o convite e a intenção do e-mail na transação corrente, que deve ter o contexto da unidade e a
     * identidade de quem convida ({@code PlatformAccess.asPlatformAdminIn}). Invalida o convite em aberto
     * para o mesmo e-mail na unidade. Quem chama já autorizou o ator a convidar para este papel (admin
     * fastPay para o dono, H10; equipe, H11).
     */
    public UUID issue(String email, MembershipRole role, UUID inviterId, String correlationId) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(inviterId, "inviterId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("O convite deve ser gravado na transação da unidade.");
        }
        String normalized = AuthKeys.normalizeEmail(email);
        String inviterName = store.currentIdentityName(inviterId)
                .orElseThrow(() -> new IllegalStateException("Quem convida precisa estar no contexto da transação."));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        store.invalidateOpen(normalized, now);
        store.insert(id, normalized, role, inviterId, inviterName, now);
        jobs.enqueue(new JobRequest(EMAIL_JOB_KIND, id.toString(), null, correlationId));
        return id;
    }

    /**
     * Convite mais recente da unidade para o papel, na transação corrente (contexto da unidade ou de admin
     * fastPay).
     */
    public Optional<Summary> latest(UUID establishmentId, MembershipRole role) {
        Instant now = clock.instant();
        return store.latest(establishmentId, role).map(i -> summary(i, now));
    }

    static Summary summary(Invitation i, Instant now) {
        Status status;
        if (i.acceptedAt() != null) {
            status = Status.ACEITO;
        } else if (i.invalidatedAt() != null) {
            status = Status.INVALIDADO;
        } else if (i.sentAt() == null) {
            status = Status.ENVIO_PENDENTE;
        } else if (!now.isBefore(i.expiresAt())) {
            status = Status.VENCIDO;
        } else {
            status = Status.ENVIADO;
        }
        return new Summary(i.id(), i.email(), i.role(), status, i.createdAt(), i.sentAt(), i.expiresAt(), i.acceptedAt());
    }
}
