package tech.malyah.fastpay.bills;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.attendance.ConsumerIssues;
import tech.malyah.fastpay.bills.Bills.Done;
import tech.malyah.fastpay.bills.Bills.Invalid;
import tech.malyah.fastpay.bills.Bills.Outcome;
import tech.malyah.fastpay.bills.Bills.Refusal;
import tech.malyah.fastpay.bills.Bills.Refused;
import tech.malyah.fastpay.bills.Bills.Snapshot;
import tech.malyah.fastpay.bills.domain.BillRules;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore.BillVersionRow;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.UnitMembership;
import tech.malyah.fastpay.production.Production;
import tech.malyah.fastpay.production.Production.PendingItem;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.GuestAccess;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.BillTab;
import tech.malyah.fastpay.tabs.Tabs.Closure;
import tech.malyah.fastpay.tabs.Tabs.ClosureKind;
import tech.malyah.fastpay.tabs.Tabs.OrderTarget;

/**
 * Conferência, fechamento e reabertura da comanda (H28, FR-31–FR-33, CA-07, CA-08, CA-11; T-03 da H28, aprovada por
 * Diego em 07/11/2026).
 *
 * <ul>
 * <li><b>Iniciar</b>: a equipe com {@link Permission#OPERAR_COMANDA} ou o cliente pelo próprio acesso, confirmando o
 * total que está vendo. Exige todas as unidades enviadas entregues ou canceladas (aguardando, em preparo, pronto ou
 * impossível impedem), nenhum problema do consumidor em aberto e nenhum desconto em revisão. A comanda fica travada
 * ({@link Tabs#lockForOrderInCurrentUnit}, a mesma trava do envio, do cancelamento, da transferência e do desfazer
 * entrega): um lançamento concorrente entra antes da consolidação ou é recusado (CA-07). Grava a versão 1 da conta
 * consolidada.</li>
 * <li><b>Em fechamento</b>: pedido, cancelamento, transferência, desconto, couvert, vínculo de continuação e desfazer
 * entrega são recusados; o serviço continua ajustável e cada mudança grava a versão seguinte ({@link Bills}). A
 * cobrança (H30) usa a versão mais alta e o total dela.</li>
 * <li><b>Reabrir</b>: só com {@link Permission#REABRIR_COMANDA} e motivo; a comanda volta a receber consumo com a
 * configuração capturada na abertura. Contrato para H30/H31: cobrança pendente ou incerta recusa a reabertura (o
 * resultado incerto mantém o bloqueio) e a comanda paga é encerrada, que já recusa.</li>
 * <li><b>Encerrar sem valor</b> (H29, FR-41, CA-20, D-02): com a conta zerada e elegível (nada pendente, nenhum problema
 * em aberto, nenhum desconto em revisão), o cliente pelo próprio acesso ou a equipe encerra sem autorização, PSP,
 * pagamento ou comissão: "Encerrada sem valor a pagar". Aberta, consolida e encerra na mesma transação; em fechamento,
 * encerra a versão mais alta. Contrato para H30: cobrança pendente ou incerta também recusa.</li>
 * <li><b>Abandono</b> (H29, FR-42): a equipe encerra, com motivo, a comanda sem consumo (nenhuma unidade ativa) nem
 * pendência e com a conta zerada. Abandono com valor é encerramento com débito, do gerente (H34).</li>
 * </ul>
 */
@Service
public class Closings {

    private static final Logger log = LoggerFactory.getLogger(Closings.class);

    /** Atendimento da comanda (PRD §16). */
    public enum State {
        ABERTA, EM_FECHAMENTO, ENCERRADA
    }

    /**
     * O que impede iniciar o fechamento agora. {@code pendingItems}: unidades não entregues nem canceladas;
     * {@code openIssue}: problema do consumidor não tratado; {@code discountReview}: desconto aguardando revisão.
     */
    public record Blockers(List<PendingItem> pendingItems, boolean openIssue, boolean discountReview) {

        public boolean none() {
            return pendingItems.isEmpty() && !openIssue && !discountReview;
        }
    }

    /** Versão consolidada da conta: a que a cobrança vai usar. */
    public record Version(UUID id, int number, String total, Instant at) {
    }

    /**
     * Encerramento como a equipe o vê (H29). {@code closedByName}: quem da equipe encerrou, nulo quando foi o cliente
     * ({@code byConsumer}); {@code reason}: só no abandono.
     */
    public record ClosureView(ClosureKind kind, Instant closedAt, String closedByName, boolean byConsumer,
            String reason) {
    }

    /** Encerramento como o cliente o vê: como e quando, sem nomes nem motivo da equipe. */
    public record GuestClosureView(ClosureKind kind, Instant closedAt) {
    }

    /**
     * Fechamento como a equipe o vê. {@code total}: o da versão consolidada em fechamento, ou o de agora.
     * {@code blockers}: só enquanto aberta. {@code closure}: só encerrada (H29). {@code canStart}/{@code canReopen}/
     * {@code canCloseWithoutValue}/{@code canAbandon}: o servidor confere de novo ao fazer.
     */
    public record ClosingView(UUID tabId, State state, UUID closingId, Instant startedAt, Version version,
            String total, Blockers blockers, ClosureView closure, boolean canStart, boolean canReopen,
            boolean canCloseWithoutValue, boolean canAbandon) {
    }

    /** Fechamento como o cliente o vê: sem quem iniciou nem a permissão de reabrir. */
    public record GuestClosingView(UUID tabId, State state, Instant startedAt, Integer versionNumber, String total,
            Blockers blockers, GuestClosureView closure, boolean canStart, boolean canCloseWithoutValue) {
    }

    private final Memberships memberships;
    private final GuestAccess guests;
    private final Tabs tabs;
    private final Bills bills;
    private final Production production;
    private final ConsumerIssues issues;
    private final JdbcBillStore store;
    private final Changes changes;
    private final Clock clock;

    Closings(Memberships memberships, GuestAccess guests, Tabs tabs, Bills bills, Production production,
            ConsumerIssues issues, JdbcBillStore store, Changes changes, Clock clock) {
        this.memberships = memberships;
        this.guests = guests;
        this.tabs = tabs;
        this.bills = bills;
        this.production = production;
        this.issues = issues;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    // Equipe

    public Outcome<ClosingView> view(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> tabs
                .forBillInCurrentUnit(tabId, false)
                .<Outcome<ClosingView>>map(tab -> new Done<>(view(tab, me)))
                .orElseGet(() -> new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA)));
    }

    /** Inicia o fechamento pela equipe, com o total que a tela mostrava. */
    public Outcome<ClosingView> start(UUID actorId, UUID establishmentId, UUID tabId, String expectedTotal,
            String correlationId) {
        Optional<BigDecimal> expected = total(expectedTotal);
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (expected.isEmpty()) {
                return invalidTotal();
            }
            Optional<Refusal> refused = start(tabId, expected.get(), actorId, null, correlationId);
            return refused.<Outcome<ClosingView>>map(Refused::new)
                    .orElseGet(() -> new Done<>(view(tabs.forBillInCurrentUnit(tabId, false).orElseThrow(), me)));
        });
    }

    /**
     * Reabre a comanda em fechamento (FR-33), com motivo de 5 a 300 caracteres. {@code closingId}: o fechamento que a
     * tela mostrava; outro recusa com {@link Refusal#VERSAO_DESATUALIZADA}.
     */
    public Outcome<ClosingView> reopen(UUID actorId, UUID establishmentId, UUID tabId, UUID closingId, String reason,
            String correlationId) {
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.REABRIR_COMANDA, me -> {
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique por que a comanda volta a ser aberta, em 5 a 300 caracteres.");
            }
            OrderTarget target = tabs.lockForOrderInCurrentUnit(tabId);
            switch (target) {
                case NAO_ENCONTRADA -> {
                    return new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA);
                }
                case ENCERRADA -> {
                    return new Refused<>(Refusal.COMANDA_ENCERRADA);
                }
                case ABERTA -> {
                    return new Refused<>(Refusal.COMANDA_NAO_EM_FECHAMENTO);
                }
                case EM_FECHAMENTO -> {
                    // segue
                }
            }
            BillTab tab = tabs.forBillInCurrentUnit(tabId, false).orElseThrow();
            if (!tab.closingId().equals(closingId)) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            // H30: cobrança pendente ou incerta recusa aqui, antes de reabrir (FR-33).
            tabs.reopenInCurrentUnit(tabId, closingId, actorId, why.get(), correlationId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            return new Done<>(view(tabs.forBillInCurrentUnit(tabId, false).orElseThrow(), me));
        });
    }

    /**
     * Encerra a conta zerada pela equipe (H29, FR-41), por exemplo pelo cliente sem celular: aberta ou em fechamento,
     * sem valor a pagar e sem pendência.
     */
    public Outcome<ClosingView> closeWithoutValue(UUID actorId, UUID establishmentId, UUID tabId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> finish(tabId,
                ClosureKind.SEM_VALOR, actorId, null, null, correlationId)
                .<Outcome<ClosingView>>map(Refused::new)
                .orElseGet(() -> new Done<>(view(tabs.forBillInCurrentUnit(tabId, false).orElseThrow(), me))));
    }

    /**
     * Encerra a comanda abandonada sem consumo (H29, FR-42), com motivo de 5 a 300 caracteres: nenhuma unidade ativa,
     * conta zerada e nenhuma pendência. Com valor, é encerramento com débito (H34).
     */
    public Outcome<ClosingView> abandon(UUID actorId, UUID establishmentId, UUID tabId, String reason,
            String correlationId) {
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique por que a comanda é encerrada sem consumo, em 5 a 300 "
                        + "caracteres.");
            }
            return finish(tabId, ClosureKind.ABANDONO, actorId, null, why.get(), correlationId)
                    .<Outcome<ClosingView>>map(Refused::new)
                    .orElseGet(() -> new Done<>(view(tabs.forBillInCurrentUnit(tabId, false).orElseThrow(), me)));
        });
    }

    // Consumidor

    /** Fechamento da comanda para o cliente com acesso válido; vazio sem acesso. */
    public Optional<GuestClosingView> ofGuestTab(List<String> grantTokens, UUID tabId) {
        return guests.inTab(grantTokens, tabId, context -> tabs.forBillInCurrentUnit(tabId, false)
                .map(this::guestView).orElse(null));
    }

    /** O cliente inicia o fechamento pelo próprio acesso (UJ-01), com o total que vê. Vazio sem acesso válido. */
    public Optional<Outcome<GuestClosingView>> startAsGuest(List<String> grantTokens, UUID tabId,
            String expectedTotal, String correlationId) {
        Optional<BigDecimal> expected = total(expectedTotal);
        return guests.inTab(grantTokens, tabId, context -> {
            if (expected.isEmpty()) {
                return invalidTotal();
            }
            Optional<Refusal> refused = start(tabId, expected.get(), null, context.grantId(), correlationId);
            return refused.<Outcome<GuestClosingView>>map(Refused::new)
                    .orElseGet(() -> new Done<>(guestView(tabs.forBillInCurrentUnit(tabId, false).orElseThrow())));
        });
    }

    /**
     * O cliente encerra a própria conta zerada (H29, FR-41, CA-20), sem autorização nem pagamento. Vazio sem acesso
     * válido.
     */
    public Optional<Outcome<GuestClosingView>> closeWithoutValueAsGuest(List<String> grantTokens, UUID tabId,
            String correlationId) {
        return guests.inTab(grantTokens, tabId, context -> finish(tabId, ClosureKind.SEM_VALOR, null,
                context.grantId(), null, correlationId)
                .<Outcome<GuestClosingView>>map(Refused::new)
                .orElseGet(() -> new Done<>(guestView(tabs.forBillInCurrentUnit(tabId, false).orElseThrow()))));
    }

    // Contrato para a cobrança (H30)

    /**
     * Versão consolidada que vale para a cobrança, na transação de unidade de quem cobra, depois de travar a comanda:
     * vazia se a comanda não está em fechamento.
     */
    public Optional<Version> currentVersionInCurrentUnit(UUID tabId) {
        return tabs.forBillInCurrentUnit(tabId, false).filter(t -> t.open() && t.closingId() != null)
                .flatMap(t -> store.currentBillVersion(t.closingId())).map(Closings::version);
    }

    // Interno

    /** Trava a comanda, confere a elegibilidade e o total visto, inicia o fechamento e grava a versão 1. */
    private Optional<Refusal> start(UUID tabId, BigDecimal expected, UUID actorId, UUID grantId,
            String correlationId) {
        switch (tabs.lockForOrderInCurrentUnit(tabId)) {
            case NAO_ENCONTRADA -> {
                return Optional.of(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            case ENCERRADA -> {
                return Optional.of(Refusal.COMANDA_ENCERRADA);
            }
            case EM_FECHAMENTO -> {
                return Optional.of(Refusal.COMANDA_EM_FECHAMENTO);
            }
            case ABERTA -> {
                // segue
            }
        }
        Snapshot snapshot = bills.snapshotInCurrentUnit(tabId).orElseThrow();
        Blockers blockers = blockers(tabId, snapshot);
        if (!blockers.pendingItems().isEmpty()) {
            return Optional.of(Refusal.ITENS_PENDENTES);
        }
        if (blockers.openIssue()) {
            return Optional.of(Refusal.PROBLEMA_EM_ABERTO);
        }
        if (blockers.discountReview()) {
            return Optional.of(Refusal.DESCONTO_EM_REVISAO);
        }
        if (snapshot.composition().total().compareTo(expected) != 0) {
            return Optional.of(Refusal.CONTA_MUDOU);
        }
        UUID closingId = tabs.startClosingInCurrentUnit(tabId, actorId, grantId, correlationId);
        bills.recordVersion(closingId, snapshot, actorId, grantId, clock.instant());
        changes.publishInCurrentTransaction(Topic.CONTA, tabId);
        log.info("Conta da comanda {} consolidada (fechamento {}, total {})", tabId, closingId,
                snapshot.composition().total());
        return Optional.empty();
    }

    /**
     * Trava a comanda, confere a elegibilidade do encerramento sem valor e encerra (H29). Aberta: consolida a versão 1
     * e encerra na mesma transação; em fechamento: encerra a versão mais alta, que precisa ser zero.
     */
    private Optional<Refusal> finish(UUID tabId, ClosureKind kind, UUID actorId, UUID grantId, String reason,
            String correlationId) {
        OrderTarget target = tabs.lockForOrderInCurrentUnit(tabId);
        switch (target) {
            case NAO_ENCONTRADA -> {
                return Optional.of(Refusal.COMANDA_NAO_ENCONTRADA);
            }
            case ENCERRADA -> {
                return Optional.of(Refusal.COMANDA_ENCERRADA);
            }
            case ABERTA, EM_FECHAMENTO -> {
                // segue
            }
        }
        Snapshot snapshot = bills.snapshotInCurrentUnit(tabId).orElseThrow();
        Blockers blockers = blockers(tabId, snapshot);
        if (!blockers.pendingItems().isEmpty()) {
            return Optional.of(Refusal.ITENS_PENDENTES);
        }
        if (blockers.openIssue()) {
            return Optional.of(Refusal.PROBLEMA_EM_ABERTO);
        }
        if (blockers.discountReview()) {
            return Optional.of(Refusal.DESCONTO_EM_REVISAO);
        }
        if (kind == ClosureKind.ABANDONO && snapshot.hasItems()) {
            return Optional.of(Refusal.COMANDA_COM_CONSUMO);
        }
        // H30: cobrança pendente ou incerta recusa aqui também (FR-41).
        UUID closingId;
        if (target == OrderTarget.ABERTA) {
            if (snapshot.composition().total().signum() != 0) {
                return Optional.of(Refusal.CONTA_COM_VALOR);
            }
            closingId = tabs.startClosingInCurrentUnit(tabId, actorId, grantId, correlationId);
            bills.recordVersion(closingId, snapshot, actorId, grantId, clock.instant());
        } else {
            closingId = tabs.forBillInCurrentUnit(tabId, false).orElseThrow().closingId();
        }
        BillVersionRow version = store.currentBillVersion(closingId).orElseThrow();
        if (version.total().signum() != 0) {
            return Optional.of(Refusal.CONTA_COM_VALOR);
        }
        tabs.closeInCurrentUnit(tabId, closingId, version.id(), kind, actorId, grantId, reason, correlationId);
        changes.publishInCurrentTransaction(Topic.CONTA, tabId);
        log.info("Comanda {} encerrada sem valor ({}, fechamento {})", tabId, kind, closingId);
        return Optional.empty();
    }

    private Blockers blockers(UUID tabId, Snapshot snapshot) {
        return new Blockers(production.pendingOfTabInCurrentUnit(tabId), issues.blocksChargeInCurrentUnit(tabId),
                snapshot.reviewPending());
    }

    /**
     * Leitura do fechamento. {@code withoutValue}: encerrar sem valor é possível agora (conta zerada e nada pendente);
     * {@code empty}: e sem nenhuma unidade ativa (abandono).
     */
    private record Read(State state, Version version, String total, Blockers blockers, Closure closure,
            boolean withoutValue, boolean empty) {
    }

    private Read read(BillTab tab) {
        Snapshot snapshot = bills.snapshotInCurrentUnit(tab.id()).orElseThrow();
        if (!tab.open()) {
            return new Read(State.ENCERRADA, null, money(snapshot), null,
                    tabs.closureInCurrentUnit(tab.id()).orElse(null), false, false);
        }
        if (tab.closingId() != null) {
            Version version = store.currentBillVersion(tab.closingId()).map(Closings::version).orElseThrow();
            boolean free = new BigDecimal(version.total()).signum() == 0 && blockers(tab.id(), snapshot).none();
            return new Read(State.EM_FECHAMENTO, version, version.total(), null, null, free,
                    free && !snapshot.hasItems());
        }
        Blockers blockers = blockers(tab.id(), snapshot);
        boolean free = blockers.none() && snapshot.composition().total().signum() == 0;
        return new Read(State.ABERTA, null, money(snapshot), blockers, null, free, free && !snapshot.hasItems());
    }

    private ClosingView view(BillTab tab, UnitMembership me) {
        Read r = read(tab);
        Instant startedAt = r.version() == null ? null : startedAt(tab);
        ClosureView closure = r.closure() == null ? null : new ClosureView(r.closure().kind(),
                r.closure().closedAt(), r.closure().closedBy() == null ? null
                        : memberships.namesInCurrentUnit(List.of(r.closure().closedBy())).get(r.closure().closedBy()),
                r.closure().byConsumer(), r.closure().reason());
        return new ClosingView(tab.id(), r.state(), r.state() == State.EM_FECHAMENTO ? tab.closingId() : null,
                startedAt, r.version(), r.total(), r.blockers(), closure,
                r.state() == State.ABERTA && r.blockers().none(),
                r.state() == State.EM_FECHAMENTO && me.has(Permission.REABRIR_COMANDA), r.withoutValue(),
                r.empty());
    }

    private GuestClosingView guestView(BillTab tab) {
        Read r = read(tab);
        return new GuestClosingView(tab.id(), r.state(), r.version() == null ? null : startedAt(tab),
                r.version() == null ? null : r.version().number(), r.total(), r.blockers(),
                r.closure() == null ? null : new GuestClosureView(r.closure().kind(), r.closure().closedAt()),
                r.state() == State.ABERTA && r.blockers().none(), r.withoutValue());
    }

    private static Instant startedAt(BillTab tab) {
        return tab.closingStartedAt();
    }

    private static Version version(BillVersionRow r) {
        return new Version(r.id(), r.number(), BillRules.money(r.total()).toPlainString(), r.createdAt());
    }

    private static String money(Snapshot s) {
        return BillRules.money(s.composition().total()).toPlainString();
    }

    /** Total visto na tela: decimal com até 2 casas, não negativo. */
    static Optional<BigDecimal> total(String value) {
        if (value == null || !value.strip().matches("\\d{1,17}(\\.\\d{1,2})?")) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(value.strip()));
    }

    private static <T> Outcome<T> invalidTotal() {
        return new Invalid<>("expectedTotal", "Atualize a conta e confira o total antes de fechar.");
    }
}
