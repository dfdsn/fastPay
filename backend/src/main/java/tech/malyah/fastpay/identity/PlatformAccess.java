package tech.malyah.fastpay.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Ações da plataforma, exclusivas do admin fastPay (H10, FR-01). O papel global é conferido no banco, na
 * mesma transação do trabalho: promoção ou remoção do papel valem já na operação seguinte. O papel global
 * não abre os dados operacionais das lojas; as policies de RLS só liberam o registro de unidades e convites.
 */
@Service
public class PlatformAccess {

    private final TenantTransactions tenants;
    private final JdbcTemplate jdbc;

    PlatformAccess(TenantTransactions tenants, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /** A identidade tem o papel global de admin fastPay agora (para mostrar a área da plataforma). */
    public boolean isPlatformAdmin(UUID identityId) {
        return tenants.asIdentity(identityId,
                () -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_platform_admin()", Boolean.class)));
    }

    /**
     * Trabalho no contexto da identidade, sem unidade (listar o registro de unidades).
     *
     * @throws PlatformAccessDeniedException se a identidade não é admin fastPay
     */
    public <T> T asPlatformAdmin(UUID identityId, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return tenants.asIdentity(identityId, () -> {
            requirePlatformAdmin();
            return work.get();
        });
    }

    /**
     * Trabalho no contexto de uma unidade, como admin fastPay: criar a unidade ou reenviar o convite do dono.
     * Tudo o que o trabalho grava (unidade, convite, job, auditoria) fica na mesma transação.
     *
     * @throws PlatformAccessDeniedException se a identidade não é admin fastPay
     */
    public <T> T asPlatformAdminIn(UUID identityId, UUID establishmentId, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return tenants.inEstablishmentAs(establishmentId, identityId, () -> {
            requirePlatformAdmin();
            return work.get();
        });
    }

    private void requirePlatformAdmin() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_platform_admin()", Boolean.class))) {
            throw new PlatformAccessDeniedException();
        }
    }
}
