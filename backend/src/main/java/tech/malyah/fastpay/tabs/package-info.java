/**
 * Comandas, visitas e acesso do consumidor (H15, FR-07–FR-12). A equipe abre a comanda de uma pessoa (número
 * automático, nome/apelido e mesa opcional), que guarda a versão da configuração da unidade; mostra o QR de vínculo
 * (2 minutos, uso único) e, com a conferência presencial aprovada em P-07, recupera o acesso. O consumidor avulso
 * consulta a própria comanda sem login, pelo acesso guardado no navegador. APIs públicas:
 * {@link tech.malyah.fastpay.tabs.Tabs} (equipe) e {@link tech.malyah.fastpay.tabs.GuestAccess} (consumidor).
 * Continuação de visita (H26): vínculo explícito à visita de uma comanda anterior, com a permanência confirmada.
 * Pedidos chegam com H16. Fechamento (H28): a comanda guarda o estado "em fechamento" (início e reabertura com
 * motivo), e a trava da comanda é a mesma do envio, do cancelamento, da transferência e do desfazer entrega.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Comandas e acesso")
package tech.malyah.fastpay.tabs;
