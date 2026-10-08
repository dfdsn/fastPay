package tech.malyah.modulithfixture.violation.tabs;

import tech.malyah.modulithfixture.violation.billing.internal.BillingLedger;

/** Violação proposital: importa implementação interna do módulo billing. */
public class TabClosing {

    private final BillingLedger ledger = new BillingLedger();

    public BillingLedger ledger() {
        return ledger;
    }
}
