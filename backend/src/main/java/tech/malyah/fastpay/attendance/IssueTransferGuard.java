package tech.malyah.fastpay.attendance;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.attendance.infrastructure.JdbcIssueStore;
import tech.malyah.fastpay.production.TransferGuard;

/**
 * Problemas do consumidor que impedem transferir unidades (H25, FR-17): do item, bloqueia o item; da conta, bloqueia
 * entradas e saídas da comanda. Roda na transação de quem transfere, depois de travar as comandas.
 */
@Component
class IssueTransferGuard implements TransferGuard {

    private final JdbcIssueStore store;

    IssueTransferGuard(JdbcIssueStore store) {
        this.store = store;
    }

    @Override
    public Optional<Block> blockInCurrentUnit(UUID tabId, UUID orderLineId) {
        return store.pendingForTransfer(tabId, orderLineId)
                .map(bill -> bill ? Block.PROBLEMA_NA_CONTA : Block.PROBLEMA_NO_ITEM);
    }
}
