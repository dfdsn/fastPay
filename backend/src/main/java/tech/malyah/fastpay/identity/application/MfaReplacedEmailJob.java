package tech.malyah.fastpay.identity.application;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.infrastructure.JdbcCredentialStore;
import tech.malyah.fastpay.jobs.JobExecution;
import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.jobs.JobHandler;
import tech.malyah.fastpay.mail.Emails;
import tech.malyah.fastpay.mail.EmailTemplates;

/**
 * Aviso de autenticador trocado (H09, durável desde H12), gravado na mesma transação do recadastro. A chave
 * de deduplicação é o instante do recadastro (ISO-8601), usado no texto.
 */
@Component
class MfaReplacedEmailJob implements JobHandler {

    static final String KIND = "identity.email_mfa_reconfigurado";

    private final JdbcCredentialStore credentials;
    private final Emails emails;
    private final EmailTemplates templates;

    MfaReplacedEmailJob(JdbcCredentialStore credentials, Emails emails, EmailTemplates templates) {
        this.credentials = credentials;
        this.emails = emails;
        this.templates = templates;
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
        Instant at;
        try {
            at = Instant.parse(execution.dedupKey());
        } catch (DateTimeParseException invalid) {
            throw new JobFailure("Aviso de MFA com instante inválido.");
        }
        credentials.account(identityId).ifPresent(account -> emails.send(
                templates.mfaReplaced(account.email(), account.displayName(), at), execution.jobId()));
    }
}
