/**
 * Produção (H16, FR-18, FR-19): recebe as linhas enviadas pelo job {@link tech.malyah.fastpay.orders.Orders#FORWARDING_JOB},
 * gravado no mesmo commit do pedido. Cada linha ganha uma sequência de produção: aguardando preparo no setor do
 * produto ou, sem setor, pronta. Fila por setor, etapas e entrega chegam com H18.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Produção")
package tech.malyah.fastpay.production;
