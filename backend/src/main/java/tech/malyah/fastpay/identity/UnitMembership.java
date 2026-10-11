package tech.malyah.fastpay.identity;

import java.util.UUID;

/** Vínculo ativo de uma identidade numa unidade, já validado no banco. */
public record UnitMembership(UUID membershipId, UUID identityId, UUID establishmentId, String establishmentName,
                             MembershipRole role) {
}
