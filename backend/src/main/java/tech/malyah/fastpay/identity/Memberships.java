package tech.malyah.fastpay.identity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.infrastructure.JdbcMembershipStore;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Vínculos entre identidades e unidades (H07). Entrada dos outros módulos para operar numa unidade em
 * nome de uma identidade.
 */
@Service
public class Memberships {

    private final JdbcMembershipStore store;
    private final TenantTransactions tenants;

    Memberships(JdbcMembershipStore store, TenantTransactions tenants) {
        this.store = store;
        this.tenants = tenants;
    }

    /** Identidade existente, lida no contexto dela; vazio se não existe. */
    public Optional<IdentitySummary> identity(UUID identityId) {
        return tenants.asIdentity(identityId, () -> store.identity(identityId));
    }

    /**
     * Unidades em que a identidade tem vínculo ativo, por nome. Papel global sem vínculo não aparece aqui:
     * acesso de plataforma não é operação de loja.
     */
    public List<UnitMembership> activeUnits(UUID identityId) {
        return tenants.asIdentity(identityId, () -> store.activeUnitsOfCurrentIdentity(identityId));
    }

    /**
     * Executa o trabalho numa transação com o contexto da unidade, depois de confirmar, na mesma transação,
     * que a identidade tem vínculo ativo nela. Desativação ou troca de papel valem já na operação seguinte
     * (FR-06). O trabalho roda dentro dessa transação.
     *
     * @throws UnitAccessDeniedException sem vínculo ativo, unidade inexistente ou de outra pessoa
     */
    public <T> T inUnit(UUID identityId, UUID establishmentId, Function<UnitMembership, T> work) {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(work, "work");
        if (establishmentId == null) {
            throw new UnitAccessDeniedException(null);
        }
        return tenants.inEstablishment(establishmentId, () -> {
            UnitMembership membership = store.activeMembershipInCurrentUnit(identityId)
                    .orElseThrow(() -> new UnitAccessDeniedException(establishmentId));
            return work.apply(membership);
        });
    }

    /**
     * Desativa o vínculo na unidade, preservando a linha (autoria histórica) e os vínculos da mesma pessoa
     * em outras unidades, com auditoria. Quem chama já autorizou o ator para gerir equipe na unidade (a
     * permissão por ação chega em H11).
     *
     * @return {@code false} se o vínculo não existe na unidade ou já estava inativo
     */
    public boolean deactivate(UUID establishmentId, UUID membershipId, UUID actorId, String correlationId) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(actorId, "actorId");
        return tenants.inEstablishment(establishmentId, () -> {
            if (!store.deactivate(membershipId, actorId)) {
                return false;
            }
            store.audit("vinculo.desativado", membershipId, actorId, correlationId);
            return true;
        });
    }
}
