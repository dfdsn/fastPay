package tech.malyah.fastpay.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.Invitations;
import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore.Invitation;
import tech.malyah.fastpay.jobs.JobExecution;
import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.jobs.JobHandler;
import tech.malyah.fastpay.mail.Emails;
import tech.malyah.fastpay.mail.EmailTemplates;
import tech.malyah.fastpay.mail.PublicLinks;

/**
 * E-mail do convite (H10), no contexto da unidade. Como na redefinição de senha (H12), o token só existe aqui:
 * gera o token, envia e, se o e-mail saiu, grava o hash, o envio e o prazo de 48 h, na transação do job.
 * Convite já invalidado, aceito ou enviado não gera e-mail. Repetição depois de o servidor aceitar e antes do
 * commit envia outro link; só o gravado vale.
 */
@Component
class InvitationEmailJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailJob.class);

    private final JdbcInvitationStore invitations;
    private final Emails emails;
    private final EmailTemplates templates;
    private final PublicLinks links;
    private final Clock clock;

    InvitationEmailJob(JdbcInvitationStore invitations, Emails emails, EmailTemplates templates, PublicLinks links,
            Clock clock) {
        this.invitations = invitations;
        this.emails = emails;
        this.templates = templates;
        this.links = links;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return Invitations.EMAIL_JOB_KIND;
    }

    @Override
    public void handle(JobExecution execution) {
        if (execution.establishmentId() == null) {
            throw new JobFailure("Job de convite sem unidade.");
        }
        UUID invitationId;
        try {
            invitationId = UUID.fromString(execution.dedupKey());
        } catch (IllegalArgumentException invalid) {
            throw new JobFailure("Job de convite sem identificador de convite.");
        }
        Optional<Invitation> found = invitations.inCurrentUnit(invitationId);
        if (found.isEmpty() || !found.get().open() || found.get().sentAt() != null) {
            log.info("Convite {} não está mais aguardando envio; nada enviado", invitationId);
            return;
        }
        Invitation invitation = found.get();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Invitations.VALIDITY);
        String token = AuthKeys.newToken();
        var message = templates.invitation(invitation.email(), invitation.inviterName(),
                invitation.establishmentName(), invitation.role().label(), links.invitation(token), expiresAt);
        if (emails.send(message, execution.jobId()) != Emails.Delivery.SENT) {
            return;
        }
        invitations.markSent(invitationId, AuthKeys.sha256Hex(token), now, expiresAt);
        log.info("Convite {} enviado", invitationId);
    }
}
