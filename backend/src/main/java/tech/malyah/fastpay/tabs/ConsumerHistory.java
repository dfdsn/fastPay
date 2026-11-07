package tech.malyah.fastpay.tabs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.infrastructure.ConsumerHistoryProperties;
import tech.malyah.fastpay.tabs.infrastructure.JdbcConsumerLinkStore;
import tech.malyah.fastpay.tabs.infrastructure.JdbcConsumerLinkStore.HistoryRow;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Histórico do consumidor com Google (H27, FR-10, P-13 aprovada em 07/11/2026). A comanda entra no histórico só quando
 * a própria pessoa, conectada com Google e com o acesso válido à comanda guardado no navegador, confirma; inclusive
 * depois de encerrada, enquanto o acesso vale (FR-11). Nunca por nome, número, mesa ou e-mail. Uma conta por comanda: a
 * repetição pela mesma conta não muda nada, e outra conta é recusada. A pessoa vê as próprias comandas em todas as
 * lojas; cada loja vê só a marca "cadastrado" nas comandas dela, sem e-mail nem as outras lojas.
 */
@Service
public class ConsumerHistory {

    private static final Logger log = LoggerFactory.getLogger(ConsumerHistory.class);

    /** Situação do vínculo vista pelo consumidor com acesso à comanda. */
    public record LinkState(boolean linked, boolean mine) {
    }

    public sealed interface LinkOutcome {
    }

    /** Comanda guardada agora no histórico desta conta. */
    public record Linked() implements LinkOutcome {
    }

    /** Já estava no histórico desta conta: nada muda. */
    public record AlreadyLinked() implements LinkOutcome {
    }

    /** Já está no histórico de outra conta Google. */
    public record LinkedToAnotherAccount() implements LinkOutcome {
    }

    /** Sem acesso válido à comanda neste navegador. */
    public record NoAccess() implements LinkOutcome {
    }

    /** Comanda do histórico: loja, número, apelido e datas; sem itens, valores ou equipe. */
    public record HistoryEntry(UUID tabId, String establishmentName, int number, String name, Instant openedAt,
            Instant closedAt, Instant linkedAt) {
    }

    public record HistoryPage(List<HistoryEntry> items, int page, int size, long total) {
    }

    private final GuestAccess guests;
    private final JdbcConsumerLinkStore links;
    private final JdbcTabStore tabs;
    private final TenantTransactions tenants;
    private final Memberships memberships;
    private final Changes changes;
    private final ConsumerHistoryProperties properties;
    private final Clock clock;

    ConsumerHistory(GuestAccess guests, JdbcConsumerLinkStore links, JdbcTabStore tabs, TenantTransactions tenants,
            Memberships memberships, Changes changes, ConsumerHistoryProperties properties, Clock clock) {
        this.guests = guests;
        this.links = links;
        this.tabs = tabs;
        this.tenants = tenants;
        this.memberships = memberships;
        this.changes = changes;
        this.properties = properties;
        this.clock = clock;
    }

    /** Vazio sem acesso válido à comanda neste navegador. */
    public Optional<LinkState> state(List<String> grantTokens, UUID tabId, Optional<UUID> consumerAccountId) {
        return guests.inTab(grantTokens, tabId, guest -> links.accountOf(tabId)
                .map(owner -> new LinkState(true, consumerAccountId.filter(owner::equals).isPresent()))
                .orElseGet(() -> new LinkState(false, false)));
    }

    /** Guarda a comanda no histórico da conta; a confirmação da pessoa é conferida por quem chama. */
    public LinkOutcome link(List<String> grantTokens, UUID tabId, UUID consumerAccountId) {
        try {
            return attempt(grantTokens, tabId, consumerAccountId);
        } catch (DuplicateKeyException concurrent) {
            // Outra confirmação da mesma comanda gravou antes: vale o que ficou gravado.
            return attempt(grantTokens, tabId, consumerAccountId);
        }
    }

    private LinkOutcome attempt(List<String> grantTokens, UUID tabId, UUID consumerAccountId) {
        return guests.<LinkOutcome>inTabAsConsumer(grantTokens, tabId, consumerAccountId, guest -> {
            Optional<UUID> owner = links.accountOf(tabId);
            if (owner.isPresent()) {
                return owner.get().equals(consumerAccountId) ? new AlreadyLinked() : new LinkedToAnotherAccount();
            }
            links.insert(UUID.randomUUID(), tabId, guest.grantId(), clock.instant());
            tabs.audit("consumidor.comanda_vinculada", tabId, null, "acesso " + guest.grantId(), null);
            changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
            log.info("Comanda {} guardada no histórico de uma conta de consumidor", tabId);
            return new Linked();
        }).orElseGet(NoAccess::new);
    }

    /** Histórico da conta, das comandas abertas mais recentemente para as mais antigas. */
    public HistoryPage history(UUID consumerAccountId, int page, Integer requestedSize) {
        int size = requestedSize == null ? properties.pageSize()
                : Math.max(1, Math.min(requestedSize, properties.maxPageSize()));
        int safePage = Math.max(0, page);
        return tenants.asConsumer(consumerAccountId, () -> new HistoryPage(
                links.history(size, (long) safePage * size).stream().map(ConsumerHistory::entry).toList(), safePage,
                size, links.historySize()));
    }

    /** A comanda da unidade tem cliente cadastrado com Google (marca para a equipe, sem e-mail). */
    public Optional<Boolean> registered(UUID actorId, UUID establishmentId, UUID tabId) {
        return memberships.inUnit(actorId, establishmentId, Permission.OPERAR_COMANDA,
                me -> tabs.tab(tabId).map(t -> links.accountOf(tabId).isPresent()));
    }

    private static HistoryEntry entry(HistoryRow row) {
        return new HistoryEntry(row.tabId(), row.establishmentName(), row.number(), row.displayName(), row.openedAt(),
                row.closedAt(), row.linkedAt());
    }
}
