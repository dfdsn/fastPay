package tech.malyah.fastpay.sync;

/** Do que trata um aviso de mudança. A tela relê só o que mostra daquele tópico. */
public enum Topic {
    /** Comanda aberta, mesa trocada, acesso do consumidor vinculado ou recuperado. */
    COMANDA,
    /** Pedido enviado ou encaminhamento retomado. */
    PEDIDO,
    /** Linhas recebidas ou mudadas na produção (mensagens internas: o consumidor não recebe). */
    PRODUCAO
}
