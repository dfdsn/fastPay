/**
 * Identidade global e vínculos com estabelecimentos (H07, FR-03, FR-06, CA-03). A identidade é da
 * pessoa; o vínculo é da unidade e carrega o papel. Toda operação de loja passa por
 * {@link tech.malyah.fastpay.identity.Memberships#inUnit}, que revalida o vínculo ativo na mesma
 * transação do trabalho. Papel global (admin fastPay) não concede operação de loja. Credenciais,
 * sessões persistentes e MFA chegam em H08/H09.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identidade e vínculos")
package tech.malyah.fastpay.identity;
