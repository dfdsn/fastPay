/**
 * Catálogo da unidade (H13, FR-13, FR-14, FR-18): produtos com nome, categoria, preço, descrição e foto opcionais,
 * disponibilidade e setor de preparo (ou dispensa de produção), e os setores (cozinha, bar ou outro). API pública:
 * {@link tech.malyah.fastpay.catalog.Catalog}. Preço capturado no envio do pedido e conferência de disponibilidade
 * chegam com H16; catálogo de referência da rede em H44.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catálogo")
package tech.malyah.fastpay.catalog;
