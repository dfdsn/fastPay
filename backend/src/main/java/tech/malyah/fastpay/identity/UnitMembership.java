package tech.malyah.fastpay.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Vínculo ativo de uma identidade numa unidade, já validado no banco, com as permissões efetivas (papel mais
 * delegações vigentes, H11) lidas na mesma transação.
 */
public record UnitMembership(UUID membershipId, UUID identityId, UUID establishmentId, String establishmentName,
                             MembershipRole role, Set<Permission> permissions) {

    public UnitMembership {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** @throws PermissionDeniedException sem a permissão nesta unidade */
    public void require(Permission permission) {
        if (!has(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }
}
