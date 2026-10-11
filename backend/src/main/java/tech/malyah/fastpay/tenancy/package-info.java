/**
 * Isolamento por estabelecimento (H03). Expõe {@link tech.malyah.fastpay.tenancy.TenantTransactions},
 * que abre a transação com o contexto da unidade usado pelas policies de RLS do PostgreSQL.
 * O identificador da unidade deve vir de identidade e vínculo já validados (H07), nunca de header.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Isolamento por estabelecimento")
package tech.malyah.fastpay.tenancy;
