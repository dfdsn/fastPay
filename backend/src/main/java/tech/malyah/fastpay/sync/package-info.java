/**
 * Sincronização operacional (H17, FR-27, NFR-02, NFR-06, NFR-11; especificação §9 e §11). Cada fato que muda o que a
 * equipe ou o consumidor veem grava, na mesma transação, um aviso de mudança da unidade ({@link
 * tech.malyah.fastpay.sync.Changes#publishInCurrentTransaction}): tópico e comanda, nunca o conteúdo. Quem está com a
 * tela aberta recebe o aviso por SSE ({@link tech.malyah.fastpay.sync.Streams}) ou, sem SSE, por polling, e relê pela API
 * autorizada. Cada conexão é de um contexto (sessão da equipe numa unidade, ou acesso do consumidor a uma comanda),
 * filtrada por ele e reconferida periodicamente; heartbeat e polling não renovam a sessão.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sincronização")
package tech.malyah.fastpay.sync;
