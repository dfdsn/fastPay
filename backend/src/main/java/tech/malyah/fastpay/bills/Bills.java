package tech.malyah.fastpay.bills;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.bills.domain.BillRules;
import tech.malyah.fastpay.bills.domain.BillRules.Composition;
import tech.malyah.fastpay.bills.domain.BillRules.Discount;
import tech.malyah.fastpay.bills.domain.BillRules.Kind;
import tech.malyah.fastpay.bills.domain.CouvertRules;
import tech.malyah.fastpay.bills.domain.CouvertRules.EndKind;
import tech.malyah.fastpay.bills.domain.CouvertRules.Origin;
import tech.malyah.fastpay.bills.domain.CouvertRules.Reason;
import tech.malyah.fastpay.bills.domain.CouvertRules.VisitState;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore.CouvertRow;
import tech.malyah.fastpay.bills.infrastructure.JdbcBillStore.DiscountRow;
import tech.malyah.fastpay.establishments.CouvertMode;
import tech.malyah.fastpay.establishments.EstablishmentSettings;
import tech.malyah.fastpay.establishments.ServiceBase;
import tech.malyah.fastpay.establishments.SettingsValues;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.UnitMembership;
import tech.malyah.fastpay.orders.Orders;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.GuestAccess;
import tech.malyah.fastpay.tabs.Tabs;
import tech.malyah.fastpay.tabs.Tabs.BillTab;

/**
 * Conta da comanda (H22, PRD §10, FR-28–FR-30, FR-68, D-09, D-10): consumo, desconto, couvert, serviço e total,
 * calculados a cada leitura com {@link BillRules}, nunca confiando em valor vindo da tela.
 *
 * <ul>
 * <li><b>Consumo (C)</b>: linhas enviadas alocadas na comanda, com o preço do envio (H16).</li>
 * <li><b>Desconto (D)</b>: um por comanda, em reais ou percentual sobre o consumo, nunca sobre o couvert; quem tem
 * {@link Permission#CONCEDER_DESCONTO} concede com motivo, dentro do próprio limite (P-17) e dos formatos aceitos pela
 * configuração vigente; conceder de novo substitui. Desconto em reais efetivo = min(valor, C). Se o consumo cair e o
 * percentual efetivo passar da autoridade de quem concedeu, a conta fica em revisão e a cobrança é bloqueada
 * ({@link #blocksChargeInCurrentUnit}) até alguém com limite suficiente revisar, substituir ou retirar; correções
 * (cancelamento, transferência) continuam possíveis.</li>
 * <li><b>Couvert (K)</b>: o couvert válido lançado na comanda (H26): o automático na abertura, com o valor da versão
 * capturada; o manual na inclusão, com o valor vigente; a correção com o valor digitado. Um por comanda e um por
 * visita (FR-12, FR-29): não se repete na continuação, a dispensa por indevido/isenção vale para a visita, duplicidade
 * preserva o válido e correção exige inclusão explícita. Cancelar exige {@link Permission#CANCELAR_ITEM} e motivo.</li>
 * <li><b>Serviço (S)</b>: percentual e base da configuração capturada na abertura; o cliente (ou a equipe por ele)
 * retira ou devolve sem autorização enquanto a comanda está aberta e sem cobrança.</li>
 * </ul>
 * Comanda encerrada não muda; "em fechamento" (H28) e cobrança existente (H30) passam a recusar quando existirem.
 */
@Service
public class Bills {

    private static final Logger log = LoggerFactory.getLogger(Bills.class);

    /** Lançamento de consumo; valores em reais como texto com 2 casas (especificação §7). */
    public record Line(UUID orderLineId, String productName, int quantity, String unitPrice, String lineTotal,
            Instant sentAt) {
    }

    public record Person(UUID id, String name) {
    }

    /**
     * Desconto ativo como a equipe o vê. {@code value}: o concedido (reais ou percentual); {@code amount}: D efetivo
     * hoje; {@code authorityPercent}: limite de quem concedeu ou de quem revisou por último; {@code needsReview}: o
     * percentual efetivo passou dessa autoridade.
     */
    public record DiscountView(UUID id, Kind kind, String value, String amount, String effectivePercent, String reason,
            Person grantedBy, Instant grantedAt, String consumptionAtGrant, String authorityPercent,
            boolean needsReview, Person reviewedBy, Instant reviewedAt) {
    }

    /** Serviço da comanda: configuração capturada na abertura, a escolha do cliente, a base (B) e o valor (S). */
    public record ServiceView(boolean enabled, String percent, ServiceBase base, boolean includesCouvert,
            boolean waived, String baseAmount, String amount) {
    }

    /** O que quem está vendo pode fazer com o desconto agora (o servidor confere de novo ao fazer). */
    public record DiscountPolicy(boolean fixedAllowed, boolean percentAllowed, boolean canGrant,
            String myLimitPercent) {
    }

    /**
     * Couvert como o cliente o vê (H26): {@code COBRADO} nesta comanda; {@code COBRADO_EM_OUTRA} comanda da mesma
     * visita (não se repete); {@code DISPENSADO} na visita; {@code AGUARDANDO_CORRECAO}; {@code SEM_COUVERT}.
     */
    public enum CouvertState {
        COBRADO,
        COBRADO_EM_OUTRA,
        DISPENSADO,
        AGUARDANDO_CORRECAO,
        SEM_COUVERT
    }

    /** Couvert encerrado desta comanda, para o histórico na tela da equipe. */
    public record EndedCouvert(UUID id, Origin origin, String amount, EndKind endKind, Reason reasonKind,
            String reason, Person endedBy, Instant endedAt) {
    }

    /**
     * Couvert da comanda como a equipe o vê (H26). {@code mode}: modalidade capturada na abertura (nula sem couvert);
     * {@code chargeId}/{@code origin}/{@code amount}: o válido desta comanda; {@code chargedOnTab}: a comanda da visita
     * que tem o couvert, quando é outra; {@code includeAmount}: o valor que a inclusão manual usaria agora.
     */
    public record CouvertView(CouvertMode mode, CouvertState state, UUID chargeId, Origin origin,
            String amount, Tabs.TabRef chargedOnTab, boolean canInclude, String includeAmount, boolean canCancel,
            boolean canCorrect, List<EndedCouvert> history) {
    }

    /** Conta como a equipe a vê. {@code reviewPending}: desconto acima da autoridade, cobrança bloqueada. */
    public record Bill(UUID tabId, boolean open, List<Line> lines, String consumption, DiscountView discount,
            String couvert, CouvertView couvertDetail, ServiceView service, String total, boolean reviewPending,
            DiscountPolicy policy) {
    }

    /** Conta como o cliente a vê: valores, sem motivo nem nomes da equipe. */
    public record GuestBill(UUID tabId, boolean open, List<Line> lines, String consumption, String discount,
            String couvert, CouvertState couvertState, ServiceView service, String total) {
    }

    /** Composição para fechamento e cobrança (H28/H30), lida na transação deles depois de travar a comanda. */
    public record Snapshot(UUID tabId, UUID settingsVersionId, UUID discountId, Composition composition,
            boolean serviceWaived, boolean reviewPending) {
    }

    public enum Refusal {
        COMANDA_NAO_ENCONTRADA,
        /** Comanda encerrada (ou em fechamento, H28): a conta não muda. */
        COMANDA_ENCERRADA,
        /** O desconto mudou desde que a tela foi carregada. */
        VERSAO_DESATUALIZADA,
        /** A configuração vigente não aceita este formato de desconto. */
        FORMATO_NAO_ACEITO,
        /** Sem consumo não há sobre o que dar desconto. */
        SEM_CONSUMO,
        /** Desconto em reais maior que o consumo atual. */
        DESCONTO_ACIMA_DO_CONSUMO,
        /** Acima do limite de desconto da pessoa (P-17). */
        DESCONTO_ACIMA_DO_LIMITE,
        /** Retirar ou revisar sem desconto ativo. */
        SEM_DESCONTO,
        /** Revisar um desconto que está dentro da autoridade. */
        DESCONTO_SEM_REVISAO,
        /** A comanda foi aberta com o serviço desabilitado: não há o que retirar. */
        SERVICO_DESABILITADO,
        /** Cancelar ou devolver sem couvert válido nesta comanda. */
        SEM_COUVERT,
        /** Inclusão manual numa comanda aberta sem couvert ou com o couvert automático. */
        COUVERT_NAO_MANUAL,
        /** A configuração vigente desligou o couvert: não há valor para incluir. */
        COUVERT_DESABILITADO,
        /** A comanda já tem couvert válido. */
        COUVERT_JA_INCLUIDO,
        /** Outra comanda da mesma visita já tem o couvert (FR-12: não se repete). */
        COUVERT_JA_COBRADO_NA_VISITA,
        /** O couvert foi dispensado na visita (indevido ou isenção). */
        COUVERT_DISPENSADO_NA_VISITA,
        /** Cancelado ou devolvido por correção: só a inclusão de correção, com o valor correto. */
        COUVERT_AGUARDA_CORRECAO,
        /** Duplicidade sem outro couvert válido na visita. */
        COUVERT_SEM_DUPLICIDADE,
        /** Inclusão de correção sem couvert cancelado ou devolvido por correção. */
        COUVERT_SEM_CORRECAO
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    private final Memberships memberships;
    private final GuestAccess guests;
    private final Tabs tabs;
    private final Orders orders;
    private final EstablishmentSettings settings;
    private final JdbcBillStore store;
    private final Changes changes;
    private final Clock clock;

    Bills(Memberships memberships, GuestAccess guests, Tabs tabs, Orders orders, EstablishmentSettings settings,
            JdbcBillStore store, Changes changes, Clock clock) {
        this.memberships = memberships;
        this.guests = guests;
        this.tabs = tabs;
        this.orders = orders;
        this.settings = settings;
        this.store = store;
        this.changes = changes;
        this.clock = clock;
    }

    // Equipe

    /** Conta da comanda para quem opera comandas (garçom, gerente, administrador). */
    public Outcome<Bill> view(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> tabs
                .forBillInCurrentUnit(tabId, false)
                .<Outcome<Bill>>map(tab -> new Done<>(bill(load(tab), me)))
                .orElseGet(() -> new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA)));
    }

    /**
     * Concede o desconto da comanda, substituindo o ativo (FR-30). {@code currentDiscountId}: o desconto que a tela
     * mostrava (nulo se nenhum); diferente do atual recusa com {@link Refusal#VERSAO_DESATUALIZADA}.
     */
    public Outcome<Bill> grant(UUID actorId, UUID establishmentId, UUID tabId, UUID currentDiscountId, String kind,
            String value, String reason, String correlationId) {
        BillRules.Parsed parsed = BillRules.discount(kind, value);
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.CONCEDER_DESCONTO, me -> {
            if (parsed instanceof BillRules.Rejected r) {
                return new Invalid<>(r.field(), r.message());
            }
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique o motivo do desconto em 5 a 300 caracteres.");
            }
            Discount discount = ((BillRules.Valid) parsed).discount();
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            Loaded current = load(tab.get());
            if (!Objects.equals(currentDiscountId, current.discount().map(DiscountRow::id).orElse(null))) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            SettingsValues now = settings.currentInCurrentUnit().values();
            if (discount.kind() == Kind.FIXO ? !now.discountFixedAllowed() : !now.discountPercentAllowed()) {
                return new Refused<>(Refusal.FORMATO_NAO_ACEITO);
            }
            BigDecimal c = current.composition().consumption();
            if (c.signum() == 0) {
                return new Refused<>(Refusal.SEM_CONSUMO);
            }
            if (discount.kind() == Kind.FIXO && discount.value().compareTo(c) > 0) {
                return new Refused<>(Refusal.DESCONTO_ACIMA_DO_CONSUMO);
            }
            BigDecimal d = BillRules.discountAmount(c, discount);
            if (BillRules.needsReview(discount, d, c, me.discountLimitPercent())) {
                return new Refused<>(Refusal.DESCONTO_ACIMA_DO_LIMITE);
            }
            Instant at = clock.instant();
            current.discount().ifPresent(previous -> store.endDiscount(previous.id(), "SUBSTITUIDO", null, actorId, at));
            UUID id = UUID.randomUUID();
            store.insertDiscount(id, tabId, discount.kind(), discount.value(), why.get(), actorId, at,
                    me.discountLimitPercent(), c, d);
            store.audit("desconto.concedido", id, actorId, "comanda " + tabId + "; " + discount.kind() + " "
                    + discount.value().toPlainString() + "; consumo " + c.toPlainString() + "; desconto "
                    + d.toPlainString() + "; limite " + me.discountLimitPercent().toPlainString()
                    + current.discount().map(p -> "; substitui " + p.id()).orElse(""), correlationId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            log.info("Desconto {} concedido na comanda {} por {} ({} {})", id, tabId, actorId, discount.kind(),
                    discount.value());
            return new Done<>(bill(load(tab.get()), me));
        });
    }

    /** Retira o desconto ativo, com motivo (FR-30: autoria e motivo obrigatórios). */
    public Outcome<Bill> remove(UUID actorId, UUID establishmentId, UUID tabId, UUID discountId, String reason,
            String correlationId) {
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.CONCEDER_DESCONTO, me -> {
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique por que o desconto sai, em 5 a 300 caracteres.");
            }
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            Optional<DiscountRow> current = store.activeDiscount(tabId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.SEM_DESCONTO);
            }
            if (!current.get().id().equals(discountId)) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            store.endDiscount(discountId, "RETIRADO", why.get(), actorId, clock.instant());
            store.audit("desconto.retirado", discountId, actorId, "comanda " + tabId, correlationId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            log.info("Desconto {} retirado da comanda {} por {}", discountId, tabId, actorId);
            return new Done<>(bill(load(tab.get()), me));
        });
    }

    /**
     * Revisa o desconto que passou da autoridade de quem concedeu depois de o consumo cair (FR-68): quem revisa precisa
     * de limite que cubra o percentual efetivo de agora, e esse limite passa a ser a autoridade do desconto.
     */
    public Outcome<Bill> review(UUID actorId, UUID establishmentId, UUID tabId, UUID discountId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.CONCEDER_DESCONTO, me -> {
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            Loaded current = load(tab.get());
            if (current.discount().isEmpty()) {
                return new Refused<>(Refusal.SEM_DESCONTO);
            }
            DiscountRow row = current.discount().get();
            if (!row.id().equals(discountId)) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            if (!current.reviewPending()) {
                return new Refused<>(Refusal.DESCONTO_SEM_REVISAO);
            }
            Composition c = current.composition();
            if (BillRules.needsReview(new Discount(row.kind(), row.value()), c.discount(), c.consumption(),
                    me.discountLimitPercent())) {
                return new Refused<>(Refusal.DESCONTO_ACIMA_DO_LIMITE);
            }
            store.reviewDiscount(discountId, actorId, me.discountLimitPercent(), clock.instant());
            store.audit("desconto.revisado", discountId, actorId, "comanda " + tabId + "; consumo "
                    + c.consumption().toPlainString() + "; desconto " + c.discount().toPlainString() + "; limite "
                    + me.discountLimitPercent().toPlainString(), correlationId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            log.info("Desconto {} da comanda {} revisado por {}", discountId, tabId, actorId);
            return new Done<>(bill(load(tab.get()), me));
        });
    }

    /** A equipe retira ou devolve o serviço a pedido do cliente, sem autorização (FR-28). */
    public Outcome<Bill> chooseService(UUID actorId, UUID establishmentId, UUID tabId, Boolean waived,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            Outcome<Loaded> chosen = choose(tabId, waived, actorId, null, correlationId);
            return switch (chosen) {
                case Done<Loaded> d -> new Done<>(bill(d.value(), me));
                case Refused<Loaded> r -> new Refused<>(r.refusal());
                case Invalid<Loaded> i -> new Invalid<>(i.field(), i.message());
            };
        });
    }

    // Couvert (H26)

    /**
     * Inclui o couvert manual (FR-29, FR-68): comanda aberta no modo manual, sem couvert na visita, com o valor da
     * configuração vigente na inclusão. Quem opera comanda inclui ({@link Permission#OPERAR_COMANDA}).
     */
    public Outcome<Bill> includeCouvert(UUID actorId, UUID establishmentId, UUID tabId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            tabs.visitInCurrentUnit(tabId, true);
            Loaded current = load(tab.get());
            SettingsValues captured = current.captured();
            if (!captured.couvertEnabled() || captured.couvertMode() != CouvertMode.MANUAL) {
                return new Refused<>(Refusal.COUVERT_NAO_MANUAL);
            }
            Optional<Refusal> blocked = blockedInclusion(current);
            if (blocked.isPresent()) {
                return new Refused<>(blocked.get());
            }
            var now = settings.currentInCurrentUnit();
            if (!now.values().couvertEnabled()) {
                return new Refused<>(Refusal.COUVERT_DESABILITADO);
            }
            UUID id = UUID.randomUUID();
            store.insertCouvert(id, tabId, Origin.MANUAL, now.values().couvertAmount(), now.id(), null, actorId,
                    clock.instant());
            store.audit("couvert.incluido", id, actorId, "comanda " + tabId + "; manual "
                    + now.values().couvertAmount().toPlainString() + "; configuracao v" + now.number(), correlationId);
            changes.publishInCurrentTransaction(Topic.CONTA, tabId);
            log.info("Couvert {} incluído na comanda {} por {}", id, tabId, actorId);
            return new Done<>(bill(load(tab.get()), me));
        });
    }

    /**
     * Cancela o couvert válido da comanda aberta, com motivo (FR-12, FR-29, T-03 da H26), por quem tem
     * {@link Permission#CANCELAR_ITEM}: indevido ou isenção dispensam o couvert na visita; duplicidade exige outro
     * válido na visita, que continua cobrado; correção deixa a visita aguardando a inclusão do valor correto.
     */
    public Outcome<Bill> cancelCouvert(UUID actorId, UUID establishmentId, UUID tabId, UUID chargeId,
            String reasonKind, String reason, String correlationId) {
        Optional<Reason> kind = CouvertRules.reason(reasonKind);
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.CANCELAR_ITEM, me -> {
            if (kind.isEmpty()) {
                return new Invalid<>("reasonKind", "Escolha o motivo: indevido, isenção, duplicidade ou correção.");
            }
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique por que o couvert sai, em 5 a 300 caracteres.");
            }
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            tabs.visitInCurrentUnit(tabId, true);
            Optional<Refusal> ended = endCouvert(load(tab.get()), chargeId, EndKind.CANCELADO, kind.get(), why.get(),
                    actorId, correlationId);
            return ended.<Outcome<Bill>>map(Refused::new).orElseGet(() -> new Done<>(bill(load(tab.get()), me)));
        });
    }

    /**
     * Inclusão de correção (FR-12: "correção exige inclusão explícita do valor correto, sem recobrança automática
     * integral"): depois de cancelar ou devolver por correção, quem tem {@link Permission#CANCELAR_ITEM} inclui o valor
     * correto (R$ 0,01 a R$ 200,00), com motivo, numa comanda aberta da visita.
     */
    public Outcome<Bill> correctCouvert(UUID actorId, UUID establishmentId, UUID tabId, String amount, String reason,
            String correlationId) {
        Optional<BigDecimal> value = CouvertRules.amount(amount);
        Optional<String> why = BillRules.reason(reason);
        return memberships.inUnit(actorId, establishmentId, Permission.CANCELAR_ITEM, me -> {
            if (value.isEmpty()) {
                return new Invalid<>("amount", "Informe o valor correto do couvert, de R$ 0,01 a R$ 200,00.");
            }
            if (why.isEmpty()) {
                return new Invalid<>("reason", "Explique a correção em 5 a 300 caracteres.");
            }
            Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
            Optional<Outcome<Bill>> refused = refuseChange(tab);
            if (refused.isPresent()) {
                return refused.get();
            }
            tabs.visitInCurrentUnit(tabId, true);
            Loaded current = load(tab.get());
            if (current.visitState() != VisitState.AGUARDANDO_CORRECAO) {
                return new Refused<>(blockedInclusion(current).orElse(Refusal.COUVERT_SEM_CORRECAO));
            }
            UUID id = UUID.randomUUID();
            store.insertCouvert(id, tabId, Origin.CORRECAO, value.get(), null, why.get(), actorId, clock.instant());
            store.audit("couvert.corrigido", id, actorId, "comanda " + tabId + "; valor " + value.get().toPlainString(),
                    correlationId);
            publishVisit(current);
            log.info("Couvert {} de correção incluído na comanda {} por {}", id, tabId, actorId);
            return new Done<>(bill(load(tab.get()), me));
        });
    }

    /**
     * Contrato para a devolução (H35, FR-12, FR-71, D-12), na transação de unidade de quem registra a devolução
     * atribuída ao couvert, com identidade (quem autorizou): encerra o couvert como devolvido, com o motivo e os mesmos
     * efeitos do cancelamento. Vale também para comanda encerrada. Devolução de valor livre não chama isto (não presume
     * estorno de couvert). Vazio quando registrado; senão a recusa.
     */
    public Optional<Refusal> recordCouvertRefundInCurrentUnit(UUID tabId, UUID chargeId, Reason reason, UUID actorId,
            String correlationId) {
        Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, false);
        if (tab.isEmpty()) {
            return Optional.of(Refusal.COMANDA_NAO_ENCONTRADA);
        }
        tabs.visitInCurrentUnit(tabId, true);
        return endCouvert(load(tab.get()), chargeId, EndKind.DEVOLVIDO, reason, null, actorId, correlationId);
    }

    /** Por que a visita não aceita mais um couvert agora; vazio se aceita. */
    private static Optional<Refusal> blockedInclusion(Loaded l) {
        if (l.couvert().isPresent()) {
            return Optional.of(Refusal.COUVERT_JA_INCLUIDO);
        }
        return switch (l.visitState()) {
            case COBRADO -> Optional.of(Refusal.COUVERT_JA_COBRADO_NA_VISITA);
            case DISPENSADO -> Optional.of(Refusal.COUVERT_DISPENSADO_NA_VISITA);
            case AGUARDANDO_CORRECAO -> Optional.of(Refusal.COUVERT_AGUARDA_CORRECAO);
            case SEM_COUVERT -> Optional.empty();
        };
    }

    /** Encerra o couvert válido da comanda (cancelamento ou devolução), com a visita já travada. */
    private Optional<Refusal> endCouvert(Loaded current, UUID chargeId, EndKind kind, Reason reason, String text,
            UUID actorId, String correlationId) {
        if (current.couvert().isEmpty()) {
            return Optional.of(Refusal.SEM_COUVERT);
        }
        CouvertRow charge = current.couvert().get();
        if (!charge.id().equals(chargeId)) {
            return Optional.of(Refusal.VERSAO_DESATUALIZADA);
        }
        if (reason == Reason.DUPLICIDADE && !CouvertRules.hasDuplicate(
                current.visitCouverts().stream().map(CouvertRow::charge).toList(), charge.charge())) {
            return Optional.of(Refusal.COUVERT_SEM_DUPLICIDADE);
        }
        store.endCouvert(chargeId, kind, reason, text, actorId, clock.instant());
        store.audit(kind == EndKind.CANCELADO ? "couvert.cancelado" : "couvert.devolvido", chargeId, actorId,
                "comanda " + current.tab().id() + "; motivo " + reason.name() + "; valor "
                        + charge.amount().toPlainString(), correlationId);
        publishVisit(current);
        log.info("Couvert {} da comanda {} {} por {} ({})", chargeId, current.tab().id(),
                kind == EndKind.CANCELADO ? "cancelado" : "devolvido", actorId, reason);
        return Optional.empty();
    }

    /** A situação do couvert muda para todas as comandas da visita: cada uma recebe o aviso da conta. */
    private void publishVisit(Loaded l) {
        tabs.visitInCurrentUnit(l.tab().id(), false).map(Tabs.VisitTabs::tabIds).orElse(List.of(l.tab().id()))
                .forEach(id -> changes.publishInCurrentTransaction(Topic.CONTA, id));
    }

    // Consumidor

    /** Conta da comanda para o cliente com acesso válido; vazio sem acesso (outra comanda, revogado, vencido). */
    public Optional<GuestBill> ofGuestTab(List<String> grantTokens, UUID tabId) {
        return guests.inTab(grantTokens, tabId, context -> tabs.forBillInCurrentUnit(tabId, false)
                .map(tab -> guestBill(load(tab))).orElse(null));
    }

    /** O cliente retira ou devolve o serviço, sem autorização, antes da cobrança (FR-28). Vazio sem acesso válido. */
    public Optional<Outcome<GuestBill>> chooseServiceAsGuest(List<String> grantTokens, UUID tabId, Boolean waived,
            String correlationId) {
        return guests.inTab(grantTokens, tabId, context -> {
            Outcome<Loaded> chosen = choose(tabId, waived, null, context.grantId(), correlationId);
            return switch (chosen) {
                case Done<Loaded> d -> new Done<>(guestBill(d.value()));
                case Refused<Loaded> r -> new Refused<>(r.refusal());
                case Invalid<Loaded> i -> new Invalid<>(i.field(), i.message());
            };
        });
    }

    // Contratos para fechamento e cobrança (H28, H30)

    /**
     * Composição da conta na transação de unidade de quem chama, que já travou a comanda (H28/H30 guardam o snapshot
     * dela na cobrança). Vazio se a comanda não existe na unidade.
     */
    public Optional<Snapshot> snapshotInCurrentUnit(UUID tabId) {
        return tabs.forBillInCurrentUnit(tabId, false).map(tab -> {
            Loaded l = load(tab);
            return new Snapshot(tabId, tab.settingsVersionId(), l.discount().map(DiscountRow::id).orElse(null),
                    l.composition(), l.waived(), l.reviewPending());
        });
    }

    /**
     * Desconto em revisão bloqueia nova cobrança (FR-68), na transação de quem cobra, depois de travar a comanda. Não
     * impede cancelamento nem transferência.
     */
    public boolean blocksChargeInCurrentUnit(UUID tabId) {
        return tabs.forBillInCurrentUnit(tabId, false).map(tab -> load(tab).reviewPending()).orElse(false);
    }

    // Interno

    /**
     * {@code visitCouverts}: couverts (válidos e encerrados) de todas as comandas da visita; {@code couvert}: o válido
     * desta comanda.
     */
    private record Loaded(BillTab tab, List<Orders.ChargedLine> lines, SettingsValues captured,
            Optional<DiscountRow> discount, boolean waived, Composition composition, boolean reviewPending,
            List<CouvertRow> visitCouverts, Optional<CouvertRow> couvert) {

        VisitState visitState() {
            return CouvertRules.state(visitCouverts.stream().map(CouvertRow::charge).toList());
        }
    }

    private Loaded load(BillTab tab) {
        List<Orders.ChargedLine> lines = orders.chargedLinesOfTabInCurrentUnit(tab.id());
        BigDecimal c = lines.stream().map(Orders.ChargedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        SettingsValues captured = settings.versionInCurrentUnit(tab.settingsVersionId()).orElseThrow().values();
        List<UUID> visitTabs = tabs.visitInCurrentUnit(tab.id(), false).map(Tabs.VisitTabs::tabIds)
                .orElse(List.of(tab.id()));
        List<CouvertRow> visitCouverts = store.couverts(visitTabs);
        Optional<CouvertRow> valid = visitCouverts.stream()
                .filter(r -> r.tabId().equals(tab.id()) && r.endKind() == null).findFirst();
        BigDecimal couvert = valid.map(CouvertRow::amount).orElse(BigDecimal.ZERO);
        boolean waived = store.serviceWaived(tab.id()).orElse(false);
        Optional<DiscountRow> discount = store.activeDiscount(tab.id());
        Composition composition = BillRules.compose(c,
                discount.map(r -> new Discount(r.kind(), r.value())).orElse(null), couvert,
                new BillRules.Service(captured.serviceEnabled(), captured.servicePercent(), captured.serviceBase(),
                        captured.serviceIncludesCouvert(), waived));
        boolean reviewPending = discount.map(r -> BillRules.needsReview(new Discount(r.kind(), r.value()),
                composition.discount(), composition.consumption(), r.authorityPercent())).orElse(false);
        return new Loaded(tab, lines, captured, discount, waived, composition, reviewPending, visitCouverts, valid);
    }

    /** Comanda inexistente ou encerrada não muda de conta. */
    private static <T> Optional<Outcome<T>> refuseChange(Optional<BillTab> tab) {
        if (tab.isEmpty()) {
            return Optional.of(new Refused<>(Refusal.COMANDA_NAO_ENCONTRADA));
        }
        if (!tab.get().open()) {
            return Optional.of(new Refused<>(Refusal.COMANDA_ENCERRADA));
        }
        return Optional.empty();
    }

    /** Grava a escolha do serviço da equipe ({@code actorId}) ou do cliente ({@code grantId}). */
    private Outcome<Loaded> choose(UUID tabId, Boolean waived, UUID actorId, UUID grantId, String correlationId) {
        if (waived == null) {
            return new Invalid<>("waived", "Diga se o serviço sai ou volta para a conta.");
        }
        Optional<BillTab> tab = tabs.forBillInCurrentUnit(tabId, true);
        Optional<Outcome<Loaded>> refused = refuseChange(tab);
        if (refused.isPresent()) {
            return refused.get();
        }
        Loaded current = load(tab.get());
        if (!current.captured().serviceEnabled()) {
            return new Refused<>(Refusal.SERVICO_DESABILITADO);
        }
        if (current.waived() == waived) {
            return new Done<>(current);
        }
        store.insertServiceChoice(tabId, waived, actorId, grantId, clock.instant());
        store.audit(waived ? "servico.retirado" : "servico.devolvido", tabId, actorId,
                actorId == null ? "pelo consumidor" : "pela equipe", correlationId);
        changes.publishInCurrentTransaction(Topic.CONTA, tabId);
        log.info("Serviço {} na comanda {} ({})", waived ? "retirado" : "devolvido", tabId,
                actorId == null ? "consumidor" : actorId);
        return new Done<>(load(tab.get()));
    }

    private Bill bill(Loaded l, UnitMembership me) {
        Composition c = l.composition();
        DiscountView discount = l.discount().map(r -> discountView(r, l, c)).orElse(null);
        SettingsValues now = settings.currentInCurrentUnit().values();
        DiscountPolicy policy = new DiscountPolicy(now.discountFixedAllowed(), now.discountPercentAllowed(),
                me.has(Permission.CONCEDER_DESCONTO) && l.tab().open(),
                me.discountLimitPercent().toPlainString());
        return new Bill(l.tab().id(), l.tab().open(), lines(l), text(c.consumption()), discount, text(c.couvert()),
                couvertView(l, me, now), service(l), text(c.total()), l.reviewPending(), policy);
    }

    private static CouvertState couvertState(Loaded l) {
        if (l.couvert().isPresent()) {
            return CouvertState.COBRADO;
        }
        return switch (l.visitState()) {
            case COBRADO -> CouvertState.COBRADO_EM_OUTRA;
            case DISPENSADO -> CouvertState.DISPENSADO;
            case AGUARDANDO_CORRECAO -> CouvertState.AGUARDANDO_CORRECAO;
            case SEM_COUVERT -> CouvertState.SEM_COUVERT;
        };
    }

    private CouvertView couvertView(Loaded l, UnitMembership me, SettingsValues now) {
        CouvertState state = couvertState(l);
        Tabs.TabRef chargedOn = null;
        if (state == CouvertState.COBRADO_EM_OUTRA) {
            UUID other = l.visitCouverts().stream().filter(r -> r.endKind() == null).findFirst().orElseThrow().tabId();
            Tabs.Label label = tabs.labelsInCurrentUnit(List.of(other)).get(other);
            chargedOn = new Tabs.TabRef(other, label.number(), label.name());
        }
        List<CouvertRow> ended = l.visitCouverts().stream()
                .filter(r -> r.tabId().equals(l.tab().id()) && r.endKind() != null).toList();
        Map<UUID, String> names = memberships.namesInCurrentUnit(ended.stream().map(CouvertRow::endedBy).distinct()
                .toList());
        List<EndedCouvert> history = ended.stream().map(r -> new EndedCouvert(r.id(), r.origin(), text(r.amount()),
                r.endKind(), r.endReasonKind(), r.endReason(), new Person(r.endedBy(), names.get(r.endedBy())),
                r.endedAt())).toList();
        boolean open = l.tab().open();
        boolean manual = l.captured().couvertEnabled() && l.captured().couvertMode() == CouvertMode.MANUAL;
        boolean canInclude = open && manual && state == CouvertState.SEM_COUVERT && now.couvertEnabled();
        boolean cancelling = open && me.has(Permission.CANCELAR_ITEM);
        return new CouvertView(l.captured().couvertEnabled() ? l.captured().couvertMode() : null, state,
                l.couvert().map(CouvertRow::id).orElse(null), l.couvert().map(CouvertRow::origin).orElse(null),
                text(l.composition().couvert()), chargedOn, canInclude,
                canInclude ? text(now.couvertAmount()) : null, cancelling && l.couvert().isPresent(),
                cancelling && state == CouvertState.AGUARDANDO_CORRECAO, history);
    }

    private DiscountView discountView(DiscountRow r, Loaded l, Composition c) {
        List<UUID> people = new ArrayList<>();
        people.add(r.grantedBy());
        if (r.reviewedBy() != null) {
            people.add(r.reviewedBy());
        }
        Map<UUID, String> names = memberships.namesInCurrentUnit(people);
        return new DiscountView(r.id(), r.kind(), r.value().toPlainString(), text(c.discount()),
                BillRules.effectivePercent(c.discount(), c.consumption()).toPlainString(), r.reason(),
                new Person(r.grantedBy(), names.get(r.grantedBy())), r.grantedAt(),
                text(r.consumptionAtGrant()), r.authorityPercent().toPlainString(), l.reviewPending(),
                r.reviewedBy() == null ? null : new Person(r.reviewedBy(), names.get(r.reviewedBy())),
                r.reviewedAt());
    }

    private GuestBill guestBill(Loaded l) {
        Composition c = l.composition();
        return new GuestBill(l.tab().id(), l.tab().open(), lines(l), text(c.consumption()),
                l.discount().isPresent() ? text(c.discount()) : null, text(c.couvert()), couvertState(l), service(l),
                text(c.total()));
    }

    private static List<Line> lines(Loaded l) {
        return l.lines().stream().map(x -> new Line(x.orderLineId(), x.productName(), x.quantity(),
                text(x.unitPrice()), text(x.lineTotal()), x.sentAt())).toList();
    }

    private static ServiceView service(Loaded l) {
        SettingsValues v = l.captured();
        Composition c = l.composition();
        return new ServiceView(v.serviceEnabled(), v.servicePercent().setScale(4).toPlainString(), v.serviceBase(),
                v.serviceIncludesCouvert(), l.waived(), text(c.serviceBase()), text(c.service()));
    }

    private static String text(BigDecimal value) {
        return BillRules.money(value).toPlainString();
    }
}
