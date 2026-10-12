package tech.malyah.fastpay.identity;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Dados de teste gravados como superusuário: o runtime não cria identidades nem vínculos (isso chega
 * com convites, H10/H11).
 */
public record IdentityFixtures(JdbcTemplate admin) {

    /** Custo baixo só para acelerar os testes; o formato ({bcrypt}) é o mesmo da aplicação. */
    private static final BCryptPasswordEncoder FAST_BCRYPT = new BCryptPasswordEncoder(4);

    public static String email(UUID identityId) {
        return identityId + "@teste.invalid";
    }

    /** Senha da identidade, gravada como a aplicação grava. */
    public void credential(UUID identityId, String password) {
        admin.update("""
                INSERT INTO fastpay.user_credential (identity_id, password_hash, changed_at) VALUES (?, ?, now())
                ON CONFLICT (identity_id) DO UPDATE SET password_hash = excluded.password_hash
                """, identityId, "{bcrypt}" + FAST_BCRYPT.encode(password));
    }

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
                id, email(id), displayName, platformRole);
        return id;
    }

    public UUID membership(UUID identityId, UUID establishmentId, MembershipRole role) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO fastpay.membership (id, establishment_id, identity_id, role) VALUES (?, ?, ?, ?)",
                id, establishmentId, identityId, role.name());
        return id;
    }
}
