package tech.malyah.fastpay.tabs;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.establishments.EstablishmentSettings;
import tech.malyah.fastpay.establishments.SettingsVersion;
import tech.malyah.fastpay.establishments.UnitProfiles;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.domain.AccessTokens;
import tech.malyah.fastpay.tabs.domain.QrSvg;
import tech.malyah.fastpay.tabs.domain.TabRules;
import tech.malyah.fastpay.tabs.domain.TabRules.Recovery;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore.TabRow;
import tech.malyah.fastpay.tabs.infrastructure.TabsProperties;

/**
 * Comandas pela equipe (H15, FR-07–FR-09, FR-12, CA-01, CA-02). Quem opera comanda ({@link Permission#OPERAR_COMANDA}:
 * garçom, gerente, administrador) abre a comanda de uma pessoa, troca a mesa e mostra o QR de vínculo. Recuperar o
 * acesso do consumidor exige {@link Permission#RECUPERAR_ACESSO_CONSUMIDOR} e a conferência presencial de P-07.
 *
 * <p>A comanda nasce com a sua visita e guarda a versão da configuração da unidade vigente na abertura (H14): serviço,
 * couvert automático, atendimento e saída dessa versão valem para ela mesmo que a configuração mude depois. Só
 * unidade ativada pelo admin fastPay (H39) abre comanda em produção; em desenvolvimento e homologação a unidade em
 * configuração também abre (decisão de Diego em 03/11/2026).
 */
@Service
public class Tabs {

    private static final Logger log = LoggerFactory.getLogger(Tabs.class);

    /**
     * {@code guestLinked}: o consumidor já confirmou o vínculo e tem acesso ativo. {@code attendanceMode}: modalidade de
     * atendimento capturada na abertura (H20); {@code responsibleId}/{@code responsibleName}: o garçom responsável,
     * nulos na central. {@code continuesTab}: a comanda anterior cuja visita esta continua (H26), nula sem vínculo.
     * {@code closingSince}: início do fechamento em andamento (H28), nulo se a comanda está aberta para consumo.
     */
    public record Tab(UUID id, UUID visitId, int number, String name, String table, UUID settingsVersionId,
            Instant openedAt, boolean guestLinked, AttendanceMode attendanceMode, UUID responsibleId,
            String responsibleName, long version, TabRef continuesTab, Instant closingSince) {
    }

    /** Comanda citada por outra (H26): número e nome/apelido, só para a equipe reconhecer. */
    public record TabRef(UUID id, int number, String name) {
    }

    /**
     * Comanda que pode ser a anterior de uma continuação de visita (H26): aberta antes, dentro do prazo, de outra
     * visita e sem saída validada. A lista vem por hora de abertura, nunca por semelhança de nome (FR-12).
     */
    public record ContinuationCandidate(UUID id, int number, String name, String table, Instant openedAt,
            boolean closed) {
    }

    /** Visita da comanda para o couvert (H26): as comandas dela, da abertura mais antiga à mais nova. */
    public record VisitTabs(UUID visitId, List<UUID> tabIds) {
    }

    /** QR de vínculo para mostrar na tela: o link vai no QR; o token só existe aqui e no navegador do consumidor. */
    public record BindingQr(UUID tabId, String link, String svg, Instant expiresAt, boolean recovery) {
    }

    public enum Refusal {
        NAO_ENCONTRADA,
        VERSAO_DESATUALIZADA,
        /** Unidade ainda não ativada pelo admin fastPay (só em produção). */
        UNIDADE_NAO_ATIVA,
        /** Todos os números de 1 a 9999 estão em comandas ativas. */
        SEM_NUMERO_LIVRE,
        /** Já há acesso ativo: trocar o aparelho é recuperação, com conferência. */
        CONSUMIDOR_JA_VINCULADO,
        /** Recuperação pedida, mas ninguém confirmou o vínculo ainda: basta mostrar o QR. */
        SEM_ACESSO_A_RECUPERAR,
        /** Comanda aberta na modalidade central: não tem responsável; cada chamado é reatribuído (H20). */
        ATENDIMENTO_CENTRAL,
        /** A comanda já continua uma visita, ou outra comanda já continua a visita dela (H26): o vínculo é único. */
        COMANDA_JA_VINCULADA,
        /** A anterior não existe na unidade, é a própria, foi aberta depois ou fora do prazo de continuação (H26). */
        COMANDA_ANTERIOR_INVALIDA,
        /** A visita da anterior terminou na saída validada (H32): a nova entrada é visita nova (FR-12, CA-13). */
        VISITA_ENCERRADA_NA_SAIDA,
        /** A comanda está em fechamento (H28): a conta não muda até reabrir. */
        COMANDA_EM_FECHAMENTO
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
    private final EstablishmentSettings settings;
    private final UnitProfiles units;
    private final JdbcTabStore store;
    private final TabsProperties properties;
    private final Clock clock;
    private final boolean production;
    private final String publicBaseUrl;
    private final Changes changes;
    private final ApplicationEventPublisher events;

    Tabs(Memberships memberships, EstablishmentSettings settings, UnitProfiles units, JdbcTabStore store,
            TabsProperties properties, Clock clock, @Value("${fastpay.environment}") String environment,
            @Value("${fastpay.mail.public-base-url}") String publicBaseUrl, Changes changes,
            ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.events = events;
        this.changes = changes;
        this.settings = settings;
        this.units = units;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.production = "production".equals(environment);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    /** Abre a comanda de uma pessoa, com visita nova, número automático e a versão vigente da configuração. */
    public Outcome<Tab> open(UUID actorId, UUID establishmentId, String name, String table, String correlationId) {
        // Sem permissão a recusa é 403, mesmo com dados inválidos.
        Outcome<String> n = checked(TabRules.name(name));
        Outcome<String> t = checked(TabRules.table(table));
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (n instanceof Invalid<String> invalid) {
                return new Invalid<>(invalid.field(), invalid.message());
            }
            if (t instanceof Invalid<String> invalid) {
                return new Invalid<>(invalid.field(), invalid.message());
            }
            boolean active = units.inCurrentUnit().map(UnitProfiles.Profile::active).orElse(false);
            if (!TabRules.unitMayOpenTabs(production, active)) {
                return new Refused<>(Refusal.UNIDADE_NAO_ATIVA);
            }
            store.lockNumbering();
            Optional<Integer> number = store.freeNumber();
            if (number.isEmpty()) {
                return new Refused<>(Refusal.SEM_NUMERO_LIVRE);
            }
            SettingsVersion version = settings.currentInCurrentUnit();
            Instant now = clock.instant();
            UUID visitId = UUID.randomUUID();
            UUID tabId = UUID.randomUUID();
            store.insertVisit(visitId, actorId, now);
            // Modalidade de atendimento da versão (FR-23, FR-68): garçom responsável, quem abre assume.
            AttendanceMode mode = version.values().attendanceMode();
            store.insertTab(tabId, visitId, number.get(), ((Done<String>) n).value(), ((Done<String>) t).value(),
                    version.id(), mode == AttendanceMode.GARCOM_RESPONSAVEL ? actorId : null, actorId, now);
            store.audit("comanda.aberta", tabId, actorId, "numero " + number.get() + "; configuracao v"
                    + version.number() + "; atendimento " + mode.name(), correlationId);
            // A conta lança o couvert automático da versão capturada no mesmo commit (H26).
            events.publishEvent(new TabOpened(tabId, version.id(), actorId, now));
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            log.info("Comanda {} aberta na unidade {} por {}", tabId, establishmentId, actorId);
            return new Done<>(tab(store.tab(tabId).orElseThrow()));
        });
    }

    /** Situação da comanda para receber pedido, lida com a comanda travada (H16). */
    public enum OrderTarget {
        /** Ativa e travada até o fim da transação: o pedido entra antes de qualquer fechamento. */
        ABERTA,
        /**
         * Em fechamento (H28, FR-32), travada até o fim da transação: não recebe pedido, cancelamento, transferência
         * nem desfazer entrega até reabrir; o rascunho continua no aparelho.
         */
        EM_FECHAMENTO,
        /** Encerrada (H29–H31): não recebe pedido; o rascunho continua no aparelho. */
        ENCERRADA,
        /** Não existe nesta unidade. */
        NAO_ENCONTRADA
    }

    /**
     * Trava a comanda para o envio de pedido, na transação de unidade de quem chama (H16, FR-15, FR-31, CA-07). É o
     * contrato com o fechamento (H28/H29): quem inicia o fechamento ou encerra trava a mesma linha da comanda
     * ({@code SELECT ... FOR UPDATE}) antes de consolidar, então um envio concorrente ou entra antes da consolidação
     * ou vê a comanda encerrada e é recusado, preservando o rascunho. A permissão é conferida por quem chama.
     */
    public OrderTarget lockForOrderInCurrentUnit(UUID tabId) {
        Optional<TabRow> active = store.lockActiveTab(tabId);
        if (active.isPresent()) {
            return active.get().closingId() == null ? OrderTarget.ABERTA : OrderTarget.EM_FECHAMENTO;
        }
        // Encerrada não aparece na trava (a policy de UPDATE só cobre a ativa); a leitura distingue os casos.
        return store.tab(tabId).isPresent() ? OrderTarget.ENCERRADA : OrderTarget.NAO_ENCONTRADA;
    }

    /**
     * Comanda como a conta a usa (H22): a versão da configuração capturada na abertura, se ainda está ativa e o
     * fechamento em andamento (H28), nulo se aberta para consumo.
     */
    public record BillTab(UUID id, UUID settingsVersionId, boolean open, UUID closingId, Instant closingStartedAt) {

        /** Ativa e sem fechamento em andamento: a conta pode mudar. */
        public boolean openForConsumption() {
            return open && closingId == null;
        }
    }

    /**
     * Comanda para a conta, na transação de unidade de quem chama (H22). Com {@code lock}, a comanda ativa fica travada
     * ({@code SELECT ... FOR UPDATE}, a mesma trava do envio de pedido e do fechamento) até o fim da transação: o
     * consumo lido para conceder desconto não muda no meio. Vazio se não existe nesta unidade.
     */
    public Optional<BillTab> forBillInCurrentUnit(UUID tabId, boolean lock) {
        Optional<TabRow> active = lock ? store.lockActiveTab(tabId) : Optional.empty();
        return active.or(() -> store.tab(tabId))
                .map(row -> new BillTab(row.id(), row.settingsVersionId(), row.closedAt() == null,
                        row.closedAt() == null ? row.closingId() : null,
                        row.closedAt() == null ? row.closingStartedAt() : null));
    }

    /**
     * Inicia o fechamento (H28, FR-32), na transação de unidade de quem chama, que já travou a comanda
     * ({@link #lockForOrderInCurrentUnit} devolveu {@link OrderTarget#ABERTA}) e conferiu a elegibilidade: a equipe
     * ({@code actorId}) ou o cliente pelo próprio acesso ({@code grantId}). Devolve o id do fechamento.
     */
    public UUID startClosingInCurrentUnit(UUID tabId, UUID actorId, UUID grantId, String correlationId) {
        UUID id = UUID.randomUUID();
        store.insertClosing(id, tabId, actorId, grantId, clock.instant());
        store.audit("fechamento.iniciado", tabId, actorId, actorId == null ? "pelo consumidor" : "pela equipe",
                correlationId);
        changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
        log.info("Fechamento {} da comanda {} iniciado ({})", id, tabId, actorId == null ? "consumidor" : actorId);
        return id;
    }

    /**
     * Reabre a comanda em fechamento (H28, FR-33), na transação de unidade de quem chama, que travou a comanda e
     * conferiu a permissão e a cobrança: o fechamento termina com quem, quando e o motivo, e a comanda volta a receber
     * consumo com a mesma configuração capturada na abertura. {@code false} se o fechamento já não estava em andamento.
     */
    public boolean reopenInCurrentUnit(UUID tabId, UUID closingId, UUID actorId, String reason,
            String correlationId) {
        if (!store.reopenClosing(closingId, actorId, reason, clock.instant())) {
            return false;
        }
        store.audit("fechamento.reaberto", tabId, actorId, "fechamento " + closingId, correlationId);
        changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
        log.info("Comanda {} reaberta por {} (fechamento {})", tabId, actorId, closingId);
        return true;
    }

    /** A comanda (ativa ou encerrada) existe na unidade da transação de quem chama (H16). */
    public boolean existsInCurrentUnit(UUID tabId) {
        return store.tab(tabId).isPresent();
    }

    /**
     * Comanda como a produção, a entrega e o atendimento a identificam (H18, FR-18, H20). {@code openedBy}: quem abriu;
     * {@code attendanceMode}: a modalidade capturada na abertura; {@code responsibleId}: o garçom responsável atual,
     * nulo na central. {@code closed}: encerrada (H24 confere antes de oferecer desfazer a entrega; a decisão é tomada
     * com a comanda travada).
     */
    public record Label(UUID id, int number, String name, String table, UUID openedBy, AttendanceMode attendanceMode,
            UUID responsibleId, boolean closed) {
    }

    /** Identificação das comandas, na transação de unidade de quem chama (a permissão é conferida por ele). */
    public Map<UUID, Label> labelsInCurrentUnit(List<UUID> tabIds) {
        Map<UUID, Label> labels = new LinkedHashMap<>();
        store.labels(tabIds).forEach(r -> labels.put(r.id(),
                new Label(r.id(), r.number(), r.displayName(), r.tableLabel(), r.openedBy(),
                        AttendanceMode.valueOf(r.attendanceMode()), r.responsibleId(), r.closed())));
        return labels;
    }

    /** Comandas ativas da unidade, por número. */
    public List<Tab> active(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA,
                me -> tabs(store.activeTabs()));
    }

    public Outcome<Tab> find(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> store.tab(tabId)
                .<Outcome<Tab>>map(row -> new Done<>(tab(row)))
                .orElseGet(() -> new Refused<>(Refusal.NAO_ENCONTRADA)));
    }

    /** Troca ou retira a mesa; titular, consumo e acesso não mudam (FR-07). */
    public Outcome<Tab> changeTable(UUID actorId, UUID establishmentId, UUID tabId, long version, String table,
            String correlationId) {
        Outcome<String> t = checked(TabRules.table(table));
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (t instanceof Invalid<String> invalid) {
                return new Invalid<>(invalid.field(), invalid.message());
            }
            Optional<TabRow> current = store.lockActiveTab(tabId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            if (current.get().version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            String label = ((Done<String>) t).value();
            if (Objects.equals(current.get().tableLabel(), label)) {
                return new Done<>(tab(current.get()));
            }
            store.updateTable(tabId, version, label, actorId, clock.instant());
            store.audit("comanda.mesa_alterada", tabId, actorId, null, correlationId);
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            return new Done<>(tab(store.tab(tabId).orElseThrow()));
        });
    }

    /**
     * Reatribui o garçom responsável da comanda (H20, FR-23): só quem tem {@link Permission#REATRIBUIR_ATENDIMENTO}
     * (gerente, administrador), para alguém da equipe ativo na unidade que atende ({@link Permission#OPERAR_COMANDA}).
     * Os chamados em aberto da comanda passam ao novo responsável junto, porque o dono deles é o responsável atual. Na
     * central a comanda não tem responsável: o gerente reatribui cada chamado.
     */
    public Outcome<Tab> reassign(UUID actorId, UUID establishmentId, UUID tabId, Long version, UUID responsibleId,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.REATRIBUIR_ATENDIMENTO, me -> {
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            Optional<TabRow> current = store.lockActiveTab(tabId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            if (AttendanceMode.valueOf(current.get().attendanceMode()) == AttendanceMode.CENTRAL) {
                return new Refused<>(Refusal.ATENDIMENTO_CENTRAL);
            }
            if (current.get().version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            if (!memberships.holdsInCurrentUnit(responsibleId, Permission.OPERAR_COMANDA)) {
                return new Invalid<>("responsibleId", "Escolha alguém ativo da equipe que atende comandas.");
            }
            if (responsibleId.equals(current.get().responsibleId())) {
                return new Done<>(tab(current.get()));
            }
            store.updateResponsible(tabId, version, responsibleId, actorId, clock.instant());
            store.audit("comanda.responsavel_alterado", tabId, actorId,
                    "de " + current.get().responsibleId() + " para " + responsibleId, correlationId);
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            changes.publishInCurrentTransaction(Topic.ATENDIMENTO, tabId);
            log.info("Responsável da comanda {} trocado por {} para {}", tabId, actorId, responsibleId);
            return new Done<>(tab(store.tab(tabId).orElseThrow()));
        });
    }

    /**
     * Comandas que podem ser a anterior da continuação de visita de {@code tabId} (H26): abertas até a abertura dela e
     * dentro do prazo ({@code fastpay.tabs.visit-continuation-window}), ativas ou encerradas, de outra visita e sem
     * saída validada. Até 50, da mais recente à mais antiga.
     */
    public Outcome<List<ContinuationCandidate>> continuationCandidates(UUID actorId, UUID establishmentId,
            UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.VINCULAR_CONTINUACAO_VISITA, me -> {
            Optional<TabRow> tab = store.tab(tabId);
            if (tab.isEmpty() || tab.get().closedAt() != null) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            Instant since = clock.instant().minus(properties.visitContinuationWindow());
            return new Done<>(store.continuationCandidates(tabId, tab.get().visitId(), tab.get().openedAt(), since, 50)
                    .stream().map(r -> new ContinuationCandidate(r.id(), r.number(), r.displayName(), r.tableLabel(),
                            r.openedAt(), r.closed()))
                    .toList());
        });
    }

    /**
     * Vincula a comanda ativa à visita de uma comanda anterior do mesmo consumidor (H26, FR-12, CA-13), por quem tem
     * {@link Permission#VINCULAR_CONTINUACAO_VISITA}, com a permanência confirmada presencialmente: o sistema nunca
     * vincula nem sugere pelo nome. A anterior pode estar ativa ou encerrada; precisa ter sido aberta antes, dentro do
     * prazo de continuação, e a visita dela não pode ter terminado na saída validada (H32). A virada do dia e a saída
     * automática não encerram a visita. Cada comanda é vinculada uma vez, sem desfazer (T-03 da H26). No mesmo commit,
     * a conta aplica "couvert não se repete na visita" ({@link VisitLinked}); serviço e preços continuam por comanda.
     */
    public Outcome<Tab> linkVisit(UUID actorId, UUID establishmentId, UUID tabId, UUID previousTabId,
            Boolean stayConfirmed, Long version, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.VINCULAR_CONTINUACAO_VISITA, me -> {
            if (previousTabId == null) {
                return new Invalid<>("previousTabId", "Escolha a comanda anterior do cliente.");
            }
            if (!Boolean.TRUE.equals(stayConfirmed)) {
                return new Invalid<>("stayConfirmed",
                        "Confirme com o cliente que é a mesma pessoa e que ela não saiu do estabelecimento.");
            }
            if (version == null) {
                return new Invalid<>("version", "Atualize a tela e tente de novo.");
            }
            Optional<TabRow> current = store.lockActiveTab(tabId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            TabRow tab = current.get();
            if (tab.version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            // O couvert da comanda nova pode sair no vínculo: em fechamento a conta não muda (H28).
            if (tab.closingId() != null) {
                return new Refused<>(Refusal.COMANDA_EM_FECHAMENTO);
            }
            Optional<TabRow> previous = previousTabId.equals(tabId) ? Optional.empty() : store.tab(previousTabId);
            Instant now = clock.instant();
            if (previous.isEmpty() || previous.get().openedAt().isAfter(tab.openedAt())
                    || previous.get().openedAt().isBefore(now.minus(properties.visitContinuationWindow()))) {
                return new Refused<>(Refusal.COMANDA_ANTERIOR_INVALIDA);
            }
            UUID visitId = previous.get().visitId();
            if (tab.continuesTabId() != null || visitId.equals(tab.visitId())
                    || store.tabsOfVisit(tab.visitId()).size() > 1) {
                return new Refused<>(Refusal.COMANDA_JA_VINCULADA);
            }
            // Comanda (acima) e depois a visita de destino: a mesma ordem das mudanças de couvert (H26).
            if (store.lockVisit(visitId).orElseThrow().exitValidatedAt() != null) {
                return new Refused<>(Refusal.VISITA_ENCERRADA_NA_SAIDA);
            }
            store.moveToVisit(tabId, version, visitId, actorId, now);
            store.insertVisitLink(UUID.randomUUID(), tabId, previousTabId, tab.visitId(), visitId, actorId, now);
            store.audit("visita.vinculada", tabId, actorId, "comanda anterior " + previousTabId + "; visita "
                    + tab.visitId() + " -> " + visitId + "; permanencia confirmada", correlationId);
            events.publishEvent(new VisitLinked(tabId, previousTabId, visitId, actorId, now));
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            log.info("Comanda {} vinculada à visita {} (comanda anterior {}) por {}", tabId, visitId, previousTabId,
                    actorId);
            return new Done<>(tab(store.tab(tabId).orElseThrow()));
        });
    }

    /**
     * Visita da comanda e as comandas dela, na transação de unidade de quem chama (H26, couvert). Com {@code lock}, a
     * visita fica travada até o fim da transação; quem chama já travou a comanda antes (mesma ordem do vínculo).
     */
    public Optional<VisitTabs> visitInCurrentUnit(UUID tabId, boolean lock) {
        return store.tab(tabId).map(row -> {
            if (lock) {
                store.lockVisit(row.visitId());
            }
            return new VisitTabs(row.visitId(), store.tabsOfVisit(row.visitId()));
        });
    }

    /**
     * Contrato para a saída validada (H32, FR-12): encerra a visita da comanda, na transação de unidade de quem valida
     * a saída, para que a próxima entrada seja visita nova (couvert de novo). A saída automática não chama isto.
     * {@code false} se a comanda não existe ou a visita já estava encerrada.
     */
    public boolean endVisitByValidatedExitInCurrentUnit(UUID tabId) {
        Optional<TabRow> tab = store.tab(tabId);
        if (tab.isEmpty() || store.lockVisit(tab.get().visitId()).isEmpty()) {
            return false;
        }
        return store.endVisitByExit(tab.get().visitId(), tabId, clock.instant());
    }

    /**
     * Gera o QR de vínculo da comanda ainda sem acesso de consumidor, invalidando o QR anterior que não foi usado
     * (FR-08). Com acesso ativo, a troca de aparelho é recuperação ({@link #recover}).
     */
    public Outcome<BindingQr> bindingQr(UUID actorId, UUID establishmentId, UUID tabId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA, me -> {
            if (store.lockActiveTab(tabId).isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            if (store.activeGrant(tabId).isPresent()) {
                return new Refused<>(Refusal.CONSUMIDOR_JA_VINCULADO);
            }
            Issued issued = issue(tabId, false, actorId, clock.instant());
            store.audit("comanda.qr_gerado", tabId, actorId, null, correlationId);
            return new Done<>(issued.qr());
        });
    }

    /**
     * Recupera o acesso do consumidor (FR-09, P-07 aprovada por Diego em 03/11/2026): depois da conferência
     * presencial (nome/apelido e um item da conta, ou a hora da abertura sem pedido) e do motivo, revoga o acesso
     * atual e gera um QR novo. Consumo, chamados e pagamentos da comanda não mudam.
     */
    public Outcome<BindingQr> recover(UUID actorId, UUID establishmentId, UUID tabId, Boolean nameChecked,
            String accountCheck, String reason, String correlationId) {
        TabRules.Checked<Recovery> checked = TabRules.recovery(nameChecked, accountCheck, reason);
        return memberships.inUnit(actorId, establishmentId, Permission.RECUPERAR_ACESSO_CONSUMIDOR, me -> {
            if (checked instanceof TabRules.Rejected<Recovery> r) {
                return new Invalid<>(r.invalid().field(), r.invalid().message());
            }
            Recovery recovery = ((TabRules.Valid<Recovery>) checked).value();
            if (store.lockActiveTab(tabId).isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADA);
            }
            Optional<UUID> grant = store.activeGrant(tabId);
            if (grant.isEmpty()) {
                return new Refused<>(Refusal.SEM_ACESSO_A_RECUPERAR);
            }
            Instant now = clock.instant();
            store.revokeGrant(grant.get(), "RECUPERACAO", actorId, now);
            Issued issued = issue(tabId, true, actorId, now);
            store.insertRecovery(UUID.randomUUID(), tabId, grant.get(), issued.bindingId(), actorId, now,
                    recovery.accountCheck().name(), recovery.reason());
            store.audit("acesso.recuperado", tabId, actorId, "conferencia: nome + " + recovery.accountCheck().name(),
                    correlationId);
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            log.info("Acesso do consumidor da comanda {} recuperado por {} (acesso anterior {} revogado)", tabId,
                    actorId, grant.get());
            return new Done<>(issued.qr());
        });
    }

    private record Issued(UUID bindingId, BindingQr qr) {
    }

    /** Invalida o QR em aberto e grava o novo (só o hash); o link leva o token no fragmento, fora dos logs. */
    private Issued issue(UUID tabId, boolean recovery, UUID actorId, Instant now) {
        store.invalidateOpenBindings(tabId, now);
        String token = AccessTokens.generate();
        Instant expiresAt = now.plus(properties.bindingQrTtl());
        UUID bindingId = UUID.randomUUID();
        store.insertBinding(bindingId, tabId, AccessTokens.hash(token), recovery, actorId, now, expiresAt);
        String link = publicBaseUrl + "/c#" + token;
        return new Issued(bindingId, new BindingQr(tabId, link, QrSvg.of(link), expiresAt, recovery));
    }

    private static Outcome<String> checked(TabRules.Checked<String> checked) {
        return switch (checked) {
            case TabRules.Valid<String> v -> new Done<>(v.value());
            case TabRules.Rejected<String> r -> new Invalid<>(r.invalid().field(), r.invalid().message());
        };
    }

    /** Uma comanda, com o nome do responsável (lido na transação de quem chama). */
    private Tab tab(TabRow row) {
        return tabs(List.of(row)).getFirst();
    }

    private List<Tab> tabs(List<TabRow> rows) {
        Map<UUID, String> names = memberships.namesInCurrentUnit(rows.stream().map(TabRow::responsibleId)
                .filter(Objects::nonNull).distinct().toList());
        return rows.stream().map(row -> new Tab(row.id(), row.visitId(), row.number(), row.displayName(),
                row.tableLabel(), row.settingsVersionId(), row.openedAt(), row.guestLinked(),
                AttendanceMode.valueOf(row.attendanceMode()), row.responsibleId(),
                row.responsibleId() == null ? null : names.get(row.responsibleId()), row.version(),
                row.continuesTabId() == null ? null
                        : new TabRef(row.continuesTabId(), row.continuesNumber(), row.continuesName()),
                row.closedAt() == null ? row.closingStartedAt() : null))
                .toList();
    }
}
