package tech.malyah.fastpay.production;

import java.util.Optional;
import java.util.UUID;

/**
 * O que impede transferir unidades de um item entre comandas por causa de problema do consumidor (H25, FR-17, H21):
 * problema em aberto do item bloqueia aquele item; problema em aberto da conta bloqueia entradas e saídas da comanda.
 * Implementado pelo atendimento, que é dono dos problemas; chamado por {@link Transfers} na transação dele, com as
 * comandas já travadas.
 */
public interface TransferGuard {

    enum Block {
        /** Problema não tratado deste item nesta comanda. */
        PROBLEMA_NO_ITEM,
        /** Problema não tratado da conta desta comanda. */
        PROBLEMA_NA_CONTA
    }

    /** O bloqueio da comanda para o item, na unidade da transação de quem chama; vazio se nada impede. */
    Optional<Block> blockInCurrentUnit(UUID tabId, UUID orderLineId);
}
