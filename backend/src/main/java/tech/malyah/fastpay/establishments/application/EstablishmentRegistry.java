package tech.malyah.fastpay.establishments.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.domain.Cnpj;
import tech.malyah.fastpay.establishments.infrastructure.JdbcEstablishmentStore;
import tech.malyah.fastpay.establishments.infrastructure.JdbcEstablishmentStore.Row;
import tech.malyah.fastpay.identity.Invitations;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.PlatformAccess;

/**
 * Cadastro de estabelecimentos pelo admin fastPay (H10, FR-01, FR-02). A unidade nasce em configuração, com o
 * convite do dono (papel de administrador do estabelecimento) e o e-mail na fila, na mesma transação. Cadastrar
 * não ativa a unidade, não gera mensalidade e não libera dinheiro real: ativação é do admin fastPay (FR-72, H39)
 * e a validação cadastral fica em P-02. CNPJ é opcional nesta etapa.
 */
@Service
public class EstablishmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(EstablishmentRegistry.class);

    public record Draft(String name, String ownerEmail, String document) {
    }

    public record Establishment(UUID id, String name, String ownerEmail, String document, String status,
            Optional<Invitations.Summary> ownerInvitation) {
    }

    public sealed interface CreateOutcome {
    }

    public record Created(Establishment establishment) implements CreateOutcome {
    }

    public record Rejected(String field, String reason) implements CreateOutcome {
    }

    public sealed interface ResendOutcome {
    }

    public record Resent(Establishment establishment) implements ResendOutcome {
    }

    public record NotFound() implements ResendOutcome {
    }

    /** O dono já aceitou: a equipe passa a ser convidada por ele (H11). */
    public record OwnerAlreadyAccepted() implements ResendOutcome {
    }

    private final PlatformAccess platform;
    private final Invitations invitations;
    private final JdbcEstablishmentStore store;

    EstablishmentRegistry(PlatformAccess platform, Invitations invitations, JdbcEstablishmentStore store) {
        this.platform = platform;
        this.invitations = invitations;
        this.store = store;
    }

    /** @throws tech.malyah.fastpay.identity.PlatformAccessDeniedException se o ator não é admin fastPay */
    public CreateOutcome create(UUID actorId, Draft draft, String correlationId) {
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isEmpty() || name.length() > 120) {
            return new Rejected("name", "Informe o nome do estabelecimento, com até 120 caracteres.");
        }
        String email = draft.ownerEmail() == null ? "" : draft.ownerEmail().strip().toLowerCase(Locale.ROOT);
        if (!validEmail(email)) {
            return new Rejected("ownerEmail", "Informe um e-mail válido para o dono.");
        }
        String document = null;
        if (draft.document() != null && !draft.document().isBlank()) {
            Optional<String> cnpj = Cnpj.normalize(draft.document());
            if (cnpj.isEmpty()) {
                return new Rejected("document", "CNPJ inválido. Confira os números ou deixe em branco por enquanto.");
            }
            document = cnpj.get();
        }
        String doc = document;
        UUID id = UUID.randomUUID();
        Establishment created = platform.asPlatformAdminIn(actorId, id, () -> {
            store.insert(name, email, doc, actorId);
            store.audit("estabelecimento.criado", id, actorId, correlationId);
            UUID invitationId = invitations.issue(email, MembershipRole.ADMIN_ESTABELECIMENTO, actorId, correlationId);
            store.audit("convite.criado", invitationId, actorId, correlationId);
            return view(store.current().orElseThrow());
        });
        log.info("Estabelecimento {} cadastrado em configuração pelo admin fastPay {}", id, actorId);
        return new Created(created);
    }

    /** Unidades cadastradas, com a situação do convite do dono. */
    public List<Establishment> list(UUID actorId) {
        return platform.asPlatformAdmin(actorId, () -> store.list().stream().map(this::view).toList());
    }

    /**
     * Novo convite para o dono, que invalida o anterior (vencido, perdido ou com o e-mail na fila de falhas).
     * Recusa depois de o dono aceitar.
     */
    public ResendOutcome resendOwnerInvitation(UUID actorId, UUID establishmentId, String correlationId) {
        return platform.asPlatformAdminIn(actorId, establishmentId, () -> {
            Optional<Row> row = store.current();
            if (row.isEmpty()) {
                return new NotFound();
            }
            boolean accepted = invitations.latest(establishmentId, MembershipRole.ADMIN_ESTABELECIMENTO)
                    .map(i -> i.status() == Invitations.Status.ACEITO).orElse(false);
            if (accepted) {
                return new OwnerAlreadyAccepted();
            }
            UUID invitationId = invitations.issue(row.get().ownerEmail(), MembershipRole.ADMIN_ESTABELECIMENTO,
                    actorId, correlationId);
            store.audit("convite.reenviado", invitationId, actorId, correlationId);
            return new Resent(view(row.get()));
        });
    }

    private Establishment view(Row row) {
        return new Establishment(row.id(), row.name(), row.ownerEmail(), row.document(), row.status(),
                invitations.latest(row.id(), MembershipRole.ADMIN_ESTABELECIMENTO));
    }

    private static boolean validEmail(String email) {
        int at = email.indexOf('@');
        return email.length() >= 3 && email.length() <= 254 && at > 0 && at == email.lastIndexOf('@')
                && at < email.length() - 1 && email.chars().noneMatch(Character::isWhitespace);
    }
}
