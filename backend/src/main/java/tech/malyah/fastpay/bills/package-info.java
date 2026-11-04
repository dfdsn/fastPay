/**
 * Conta da comanda (H22, PRD §10, FR-28–FR-30, FR-68): consumo, desconto, couvert, serviço e total calculados no
 * servidor com BigDecimal e HALF_UP em 2 casas; desconto com formato, limite da pessoa, motivo e revisão quando o
 * consumo cai; retirada do serviço pelo cliente. API pública: {@link tech.malyah.fastpay.bills.Bills}. Fechamento
 * (H28), cobrança (H30) e couvert manual (H26) usam o snapshot e o bloqueio expostos aqui.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conta")
package tech.malyah.fastpay.bills;
