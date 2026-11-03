/**
 * Pedidos (H16, FR-14–FR-16, CA-05–CA-07): envio atômico do rascunho montado no aparelho do garçom, com preço e
 * disponibilidade conferidos no envio, preço capturado na linha, alocação das unidades na comanda e encaminhamento
 * durável à produção. API pública: {@link tech.malyah.fastpay.orders.Orders}. Fila e etapas da produção chegam com
 * H18; cancelamento com H23; transferência com H25.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Pedidos")
package tech.malyah.fastpay.orders;
