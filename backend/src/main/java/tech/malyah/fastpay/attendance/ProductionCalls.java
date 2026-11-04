package tech.malyah.fastpay.attendance;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.attendance.infrastructure.JdbcAttendanceStore;
import tech.malyah.fastpay.production.ProductionLineChanged;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.Tabs;

/**
 * Avisos de atendimento a partir da produção (H20, FR-21, FR-26). Roda na mesma transação da mudança da linha (o
 * evento é publicado e ouvido de forma síncrona): o aviso e o fato entram no mesmo commit, ou nenhum dos dois. Cada
 * comanda de destino ganha o seu aviso, com a modalidade de atendimento dela; entregar conclui os avisos de pronto da
 * linha, retirar a sinalização conclui os de impossibilidade, e cancelar a linha inteira (H23) conclui os dois.
 */
@Component
class ProductionCalls {

    private static final Logger log = LoggerFactory.getLogger(ProductionCalls.class);

    private final JdbcAttendanceStore store;
    private final Tabs tabs;
    private final Changes changes;

    ProductionCalls(JdbcAttendanceStore store, Tabs tabs, Changes changes) {
        this.store = store;
        this.tabs = tabs;
        this.changes = changes;
    }

    @EventListener
    void on(ProductionLineChanged event) {
        switch (event.change()) {
            case PRONTA -> open(event, CallKind.ITEM_PRONTO);
            case IMPOSSIVEL -> open(event, CallKind.IMPOSSIBILIDADE);
            case ENTREGUE -> conclude(event, CallKind.ITEM_PRONTO, "ENTREGUE");
            case IMPOSSIVEL_RETIRADO -> conclude(event, CallKind.IMPOSSIBILIDADE, "SINALIZACAO_RETIRADA");
            case CANCELADA -> {
                // H23: a linha cancelada por inteiro não tem mais o que entregar nem impossibilidade a resolver.
                conclude(event, CallKind.ITEM_PRONTO, "CANCELADO");
                conclude(event, CallKind.IMPOSSIBILIDADE, "CANCELADO");
            }
        }
    }

    private void open(ProductionLineChanged event, CallKind kind) {
        Map<UUID, Tabs.Label> labels = tabs.labelsInCurrentUnit(event.tabIds());
        int created = 0;
        for (Tabs.Label label : labels.values()) {
            if (store.open(label.id(), event.lineId(), kind, label.attendanceMode(), event.at())) {
                created++;
            }
        }
        publish(event.tabIds());
        log.info("Linha {}: {} aviso(s) de {} abertos", event.lineId(), created, kind);
    }

    private void conclude(ProductionLineChanged event, CallKind kind, String resolution) {
        int concluded = store.conclude(event.lineId(), kind, resolution, event.actorId(), event.at());
        if (concluded > 0) {
            publish(event.tabIds());
        }
    }

    private void publish(List<UUID> tabIds) {
        tabIds.forEach(tab -> changes.publishInCurrentTransaction(Topic.ATENDIMENTO, tab));
    }
}
