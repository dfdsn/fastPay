package tech.malyah.fastpay.attendance;

/** Tipo de chamado de atendimento (FR-26). Problemas do consumidor têm fila própria ({@link ConsumerIssues}, H21). */
public enum CallKind {
    /** Item pronto para entregar: termina com a entrega. */
    ITEM_PRONTO,
    /** A produção sinalizou "não é possível atender": termina quando a sinalização é retirada (ou o item cancelado). */
    IMPOSSIBILIDADE
}
