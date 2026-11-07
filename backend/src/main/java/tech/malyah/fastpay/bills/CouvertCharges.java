package tech.malyah.fastpay.bills;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.bills.domain.CouvertRules;
import tech.malyah.fastpay.bills.domain.CouvertRules.EndKind;
import tech.malyah.fastpay.bills.domain.CouvertRules.Origin;
import tech.malyah.fastpay.bills.domain.CouvertRules.VisitState;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore.CouvertRow;
import tech.malyah.fastpay.establishments.CouvertMode;
import tech.malyah.fastpay.establishments.EstablishmentSettings;
import tech.malyah.fastpay.establishments.SettingsValues;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.TabOpened;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.VisitLinked;

/**
 * Couvert lançado pelos fatos da comanda (H26, FR-12, FR-29, FR-68), na mesma transação deles (eventos síncronos): o
 * fato e o couvert entram no mesmo commit, ou nenhum dos dois.
 *
 * <ul>
 * <li>Abertura: com couvert automático na versão capturada, lança o valor dessa versão, em nome de quem abriu.</li>
 * <li>Continuação de visita: se a visita de destino já tinha couvert cobrado, dispensado ou aguardando correção, o
 * couvert válido da comanda vinculada termina como não repetido, sem recobrança. Sem couvert na visita, o da comanda
 * vinculada passa a ser o da visita.</li>
 * </ul>
 */
@Component
class CouvertCharges {

    private static final Logger log = LoggerFactory.getLogger(CouvertCharges.class);

    private final JdbcBillStore store;
    private final EstablishmentSettings settings;
    private final Tabs tabs;
    private final Changes changes;

    CouvertCharges(JdbcBillStore store, EstablishmentSettings settings, Tabs tabs, Changes changes) {
        this.store = store;
        this.settings = settings;
        this.tabs = tabs;
        this.changes = changes;
    }

    @EventListener
    void on(TabOpened event) {
        SettingsValues captured = settings.versionInCurrentUnit(event.settingsVersionId()).orElseThrow().values();
        if (!captured.couvertEnabled() || captured.couvertMode() != CouvertMode.AUTOMATICO) {
            return;
        }
        UUID id = UUID.randomUUID();
        store.insertCouvert(id, event.tabId(), Origin.AUTOMATICO, captured.couvertAmount(), event.settingsVersionId(),
                null, event.actorId(), event.at());
        log.info("Couvert automático {} lançado na comanda {}", id, event.tabId());
    }

    /** A comanda e a visita de destino já estão travadas por quem vinculou. */
    @EventListener
    void on(VisitLinked event) {
        List<UUID> visitTabs = tabs.visitInCurrentUnit(event.tabId(), false).orElseThrow().tabIds();
        List<CouvertRow> rows = store.couverts(visitTabs);
        VisitState before = CouvertRules.state(rows.stream().filter(r -> !r.tabId().equals(event.tabId()))
                .map(CouvertRow::charge).toList());
        if (before == VisitState.SEM_COUVERT) {
            return;
        }
        rows.stream().filter(r -> r.tabId().equals(event.tabId()) && r.endKind() == null).findFirst()
                .ifPresent(own -> {
                    store.endCouvert(own.id(), EndKind.NAO_REPETIDO, null, null, event.actorId(), event.at());
                    store.audit("couvert.nao_repetido", own.id(), event.actorId(), "comanda " + event.tabId()
                            + "; visita " + event.visitId() + " " + before.name(), null);
                    changes.publishInCurrentTransaction(Topic.CONTA, event.tabId());
                    log.info("Couvert {} da comanda {} não repetido na visita {} ({})", own.id(), event.tabId(),
                            event.visitId(), before);
                });
    }
}
