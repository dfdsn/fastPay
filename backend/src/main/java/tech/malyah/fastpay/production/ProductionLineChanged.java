package tech.malyah.fastpay.production;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fato da produção que interessa a quem atende (H20, FR-21, FR-26), publicado na mesma transação da mudança: quem
 * escuta (o atendimento) grava os avisos no mesmo commit. {@code tabIds}: as comandas de destino da linha.
 * {@code actorId}: quem fez a mudança; nulo quando o job de recebimento cria a linha sem preparo já pronta.
 */
public record ProductionLineChanged(UUID lineId, Change change, List<UUID> tabIds, UUID actorId, Instant at) {

    public enum Change {
        /** Pronta para entregar (marcada pela produção, ou recebida sem preparo). */
        PRONTA,
        /** Entregue a todos os destinos. */
        ENTREGUE,
        /** Produção sinalizou "não é possível atender". */
        IMPOSSIVEL,
        /** Produção retirou a sinalização. */
        IMPOSSIVEL_RETIRADO,
        /** Todas as unidades canceladas antes da entrega (H23): a linha terminou. */
        CANCELADA,
        /** A entrega foi desfeita (H24): a linha voltou a pronta e precisa ser entregue de novo. */
        ENTREGA_DESFEITA,
        /**
         * Unidades transferidas entre comandas (H25). {@code tabIds}: as comandas que ficaram com unidades ativas da
         * linha; avisos em aberto passam para elas e terminam nas que ficaram sem unidades.
         */
        TRANSFERIDA
    }

    public ProductionLineChanged {
        tabIds = List.copyOf(tabIds);
    }
}
