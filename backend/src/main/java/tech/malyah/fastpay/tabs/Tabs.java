package tech.malyah.fastpay.tabs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    /** {@code guestLinked}: o consumidor já confirmou o vínculo e tem acesso ativo. */
    public record Tab(UUID id, UUID visitId, int number, String name, String table, UUID settingsVersionId,
            Instant openedAt, boolean guestLinked, long version) {
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
        SEM_ACESSO_A_RECUPERAR
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

    Tabs(Memberships memberships, EstablishmentSettings settings, UnitProfiles units, JdbcTabStore store,
            TabsProperties properties, Clock clock, @Value("${fastpay.environment}") String environment,
            @Value("${fastpay.mail.public-base-url}") String publicBaseUrl, Changes changes) {
        this.memberships = memberships;
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
            store.insertTab(tabId, visitId, number.get(), ((Done<String>) n).value(), ((Done<String>) t).value(),
                    version.id(), actorId, now);
            store.audit("comanda.aberta", tabId, actorId, "numero " + number.get() + "; configuracao v"
                    + version.number(), correlationId);
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            log.info("Comanda {} aberta na unidade {} por {}", tabId, establishmentId, actorId);
            return new Done<>(tab(store.tab(tabId).orElseThrow()));
        });
    }

    /** Situação da comanda para receber pedido, lida com a comanda travada (H16). */
    public enum OrderTarget {
        /** Ativa e travada até o fim da transação: o pedido entra antes de qualquer fechamento. */
        ABERTA,
        /** Encerrada (H28/H29): não recebe pedido; o rascunho continua no aparelho. */
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
        if (store.lockActiveTab(tabId).isPresent()) {
            return OrderTarget.ABERTA;
        }
        // Encerrada não aparece na trava (a policy de UPDATE só cobre a ativa); a leitura distingue os casos.
        return store.tab(tabId).isPresent() ? OrderTarget.ENCERRADA : OrderTarget.NAO_ENCONTRADA;
    }

    /** A comanda (ativa ou encerrada) existe na unidade da transação de quem chama (H16). */
    public boolean existsInCurrentUnit(UUID tabId) {
        return store.tab(tabId).isPresent();
    }

    /** Comandas ativas da unidade, por número. */
    public List<Tab> active(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA,
                me -> store.activeTabs().stream().map(Tabs::tab).toList());
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

    private static Tab tab(TabRow row) {
        return new Tab(row.id(), row.visitId(), row.number(), row.displayName(), row.tableLabel(),
                row.settingsVersionId(), row.openedAt(), row.guestLinked(), row.version());
    }
}
