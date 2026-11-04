package tech.malyah.fastpay.sync;

/** Do que trata um aviso de mudança. A tela relê só o que mostra daquele tópico. */
public enum Topic {
    /** Comanda aberta, mesa trocada, acesso do consumidor vinculado ou recuperado. */
    COMANDA,
    /** Pedido enviado ou encaminhamento retomado. */
    PEDIDO,
    /** Linhas recebidas ou mudadas na produção (mensagens internas: o consumidor não recebe). */
    PRODUCAO,
    /** Chamado de atendimento criado, assumido, reatribuído ou concluído; responsável da comanda trocado (H20). */
    ATENDIMENTO,
    /** Problema do consumidor informado, atendido, encaminhado ou tratado (H21). Vai à equipe e ao consumidor. */
    PROBLEMA,
    /** Desconto concedido, revisado ou retirado; serviço retirado ou devolvido (H22). Vai à equipe e ao consumidor. */
    CONTA
}
