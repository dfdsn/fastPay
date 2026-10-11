package tech.malyah.fastpay.identity;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dados de teste gravados como superusuário: o runtime não cria identidades nem vínculos (isso chega
 * com convites, H10/H11).
 */
public record IdentityFixtures(JdbcTemplate admin) {

    public UUID establishment(String name) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO fastpay.establishment (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public UUID identity(String displayName) {
        return identity(displayName, null);
    }

    public UUID identity(String displayName, String platformRole) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO fastpay.user_identity (id, email, display_name, platform_role) VALUES (?, ?, ?, ?)",
                id, id + "@teste.invalid", displayName, platformRole);
        return id;
    }

    public UUID membership(UUID identityId, UUID establishmentId, MembershipRole role) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO fastpay.membership (id, establishment_id, identity_id, role) VALUES (?, ?, ?, ?)",
                id, establishmentId, identityId, role.name());
        return id;
    }
}
