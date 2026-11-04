package tech.malyah.fastpay.production;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.jobs.JobExecution;
import tech.malyah.fastpay.jobs.JobFailure;
import tech.malyah.fastpay.jobs.JobHandler;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.orders.Orders.ForwardedLine;
import tech.malyah.fastpay.production.infrastructure.JdbcProductionStore;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;

/**
 * Encaminhamento do pedido enviado à produção (H16, FR-15, FR-19). Roda no job gravado no commit do envio, no contexto
 * da unidade: se o processo cair entre a gravação e a produção, o job continua pendente e roda depois; reprocessar não
 * duplica (uma sequência por linha). Esgotadas as tentativas, o pedido mostra a falha e quem opera a comanda manda
 * reprocessar.
 */
@Component
class ProductionIntake implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductionIntake.class);

    // Pedidos grava o job pela fila (Jobs), que conhece este executor: buscar Pedidos na hora da execução evita o ciclo
    // na criação dos beans.
    private final ObjectProvider<Orders> orders;
    private final JdbcProductionStore store;
    private final Clock clock;
    private final Changes changes;

    ProductionIntake(ObjectProvider<Orders> orders, JdbcProductionStore store, Clock clock, Changes changes) {
        this.changes = changes;
        this.orders = orders;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return Orders.FORWARDING_JOB;
    }

    @Override
    public void handle(JobExecution execution) {
        if (execution.establishmentId() == null) {
            throw new JobFailure("Encaminhamento sem unidade.");
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(execution.dedupKey());
        } catch (IllegalArgumentException invalid) {
            throw new JobFailure("Encaminhamento com pedido inválido.");
        }
        List<ForwardedLine> lines = orders.getObject().forwardingInCurrentUnit(orderId);
        if (lines.isEmpty()) {
            throw new JobFailure("Pedido do encaminhamento não encontrado na unidade.");
        }
        Instant now = clock.instant();
        int created = 0;
        for (ForwardedLine line : lines) {
            if (store.receive(line.orderLineId(), line.sectorId(), line.quantity(), now)) {
                created++;
            }
        }
        if (created > 0) {
            // Mesma transação do job: a equipe vê a produção e o encaminhamento concluído juntos.
            changes.publishInCurrentTransaction(Topic.PRODUCAO, orders.getObject().tabInCurrentUnit(orderId).orElse(null));
        }
        log.info("Pedido {} encaminhado à produção ({} linhas novas de {})", orderId, created, lines.size());
    }
}
