/**
 * Identidade global e vínculos com estabelecimentos (H07, FR-03, FR-06, CA-03). A identidade é da
 * pessoa; o vínculo é da unidade e carrega o papel. Toda operação de loja passa por
 * {@link tech.malyah.fastpay.identity.Memberships#inUnit}, que revalida o vínculo ativo na mesma
 * transação do trabalho. Papel global (admin fastPay) não concede operação de loja. Login e sessão
 * (H08), verificação em duas etapas e comandos de operador (H09) também ficam aqui.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identidade e vínculos")
package tech.malyah.fastpay.identity;
