/**
 * Isolamento por estabelecimento (H03). Expõe {@link tech.malyah.fastpay.tenancy.TenantTransactions},
 * que abre a transação com o contexto da unidade (ou, sem unidade, da identidade, H07) usado pelas
 * policies de RLS do PostgreSQL. A unidade deve vir de identidade e vínculo já validados
 * ({@code identity.UnitAccess}), nunca de header.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Isolamento por estabelecimento")
package tech.malyah.fastpay.tenancy;
