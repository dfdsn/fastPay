/**
 * Produção (H16, H18, FR-18–FR-22): recebe as linhas enviadas pelo job
 * {@link tech.malyah.fastpay.orders.Orders#FORWARDING_JOB}, gravado no mesmo commit do pedido. Cada linha ganha uma
 * sequência de produção, para toda a quantidade: aguardando preparo no setor do produto (ou, sem setor, pronta), em
 * preparo, pronta e entregue ({@link tech.malyah.fastpay.production.Production}). Lotes chegam com H19; cancelamento e
 * ciência da produção com H23; desfazer entrega com H24.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Produção")
package tech.malyah.fastpay.production;
