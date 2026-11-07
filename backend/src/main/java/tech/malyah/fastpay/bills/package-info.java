/**
 * Conta da comanda (H22, PRD §10, FR-28–FR-30, FR-68): consumo, desconto, couvert, serviço e total calculados no
 * servidor com BigDecimal e HALF_UP em 2 casas; desconto com formato, limite da pessoa, motivo e revisão quando o
 * consumo cai; retirada do serviço pelo cliente. API pública: {@link tech.malyah.fastpay.bills.Bills}. Couvert (H26):
 * lançado por comanda, um por visita, com cancelamento por motivo, inclusão de correção e o contrato da devolução
 * (H35). Conferência, fechamento e reabertura (H28): {@link tech.malyah.fastpay.bills.Closings}, com a conta
 * consolidada em versões que a cobrança (H30) referencia.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conta")
package tech.malyah.fastpay.bills;
