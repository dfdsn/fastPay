package tech.malyah.fastpay.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore.Account;
import tech.malyah.fastpay.jobs.JobExecution;
import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.jobs.JobHandler;
import tech.malyah.fastpay.mail.Emails;
import tech.malyah.fastpay.mail.EmailTemplates;
import tech.malyah.fastpay.mail.PublicLinks;

/**
 * E-mail de redefinição de senha (H08, durável desde H12). Roda no contexto da identidade. O token só existe
 * aqui, no momento do envio: o pedido grava apenas a intenção, então nenhum token em claro fica no banco nem
 * na fila. Cada tentativa gera um link novo e só o grava se o e-mail saiu; o link anterior em aberto deixa de
 * valer (um por identidade). O prazo de 30 min conta a partir do envio.
 */
@Component
class PasswordResetEmailJob implements JobHandler {

    static final String KIND = "identity.email_redefinicao_senha";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailJob.class);

    private final JdbcCredentialStore credentials;
    private final Emails emails;
    private final EmailTemplates templates;
    private final PublicLinks links;
    private final Clock clock;

    PasswordResetEmailJob(JdbcCredentialStore credentials, Emails emails, EmailTemplates templates, PublicLinks links,
            Clock clock) {
        this.credentials = credentials;
        this.emails = emails;
        this.templates = templates;
        this.links = links;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void handle(JobExecution execution) {
        UUID identityId = execution.identityId();
        if (identityId == null) {
            throw new JobFailure("Job de conta sem identidade.");
        }
        Optional<Account> account = credentials.account(identityId);
        if (account.isEmpty()) {
            log.info("Redefinição de senha {} sem conta ativa; nada enviado", execution.jobId());
            return;
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(PasswordResets.LINK_VALIDITY);
        String token = AuthKeys.newToken();
        var message = templates.passwordReset(account.get().email(), account.get().displayName(),
                links.passwordReset(token), expiresAt);
        if (emails.send(message, execution.jobId()) != Emails.Delivery.SENT) {
            return;
        }
        credentials.invalidateOpenResetTokens(identityId, now);
        credentials.insertResetToken(identityId, AuthKeys.sha256Hex(token), now, expiresAt);
        log.info("Link de redefinição de senha enviado para a identidade {}", identityId);
    }
}
