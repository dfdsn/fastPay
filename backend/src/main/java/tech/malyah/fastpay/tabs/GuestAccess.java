package tech.malyah.fastpay.tabs;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.UnitProfiles;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Topic;
import tech.malyah.fastpay.tabs.domain.AccessTokens;
import tech.malyah.fastpay.tabs.domain.GuestAccessRules;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore.BindingRow;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore.GrantRow;
import tech.malyah.fastpay.tabs.infrastructure.JdbcTabStore.TabRow;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Acesso do consumidor avulso, sem login (H15, FR-08–FR-11, CA-01, CA-02, especificação §5). O QR de vínculo vale
 * 2 minutos e uma vez: ver o link não consome; confirmar consome numa transação, cria o acesso e o devolve para o
 * navegador guardar. O acesso dá só a própria comanda, enquanto ela está em atendimento ou com pendência e até 7 dias
 * depois da resolução final; a recuperação pela equipe revoga o anterior. Nome, número ou mesa nunca dão acesso.
 *
 * <p>Sem sessão, a unidade vem do próprio token: uma transação só enxerga a linha do hash apresentado (RLS) e diz a
 * unidade; o resto roda no contexto dela. QR ou acesso inexistente, vencido, usado, substituído ou revogado têm a
 * mesma resposta.
 */
@Service
public class GuestAccess {

    private static final Logger log = LoggerFactory.getLogger(GuestAccess.class);

    /** O que o consumidor vê antes de confirmar que a comanda é dele. */
    public record Preview(String establishmentName, int number, String name, Instant expiresAt) {
    }

    /** Comanda vista pelo consumidor. {@code open}: ainda em atendimento. */
    public record GuestTab(UUID tabId, String establishmentName, int number, String name, String table,
            Instant openedAt, boolean open) {
    }

    /** Vínculo confirmado: o token do acesso vai só para o navegador do consumidor. */
    public record Bound(String grantToken, GuestTab tab) {
    }

    /** Comanda que ainda vale para um acesso guardado no navegador. */
    public record Remembered(String grantToken, GuestTab tab) {
    }

    private final TenantTransactions tenants;
    private final JdbcTabStore store;
    private final UnitProfiles units;
    private final Clock clock;

    private final Changes changes;

    GuestAccess(TenantTransactions tenants, JdbcTabStore store, UnitProfiles units, Clock clock, Changes changes) {
        this.changes = changes;
        this.tenants = tenants;
        this.store = store;
        this.units = units;
        this.clock = clock;
    }

    /** Mostra de quem é o QR, sem consumir. Vazio se o QR não vale. */
    public Optional<Preview> preview(String bindingToken) {
        return presentedBinding(bindingToken).flatMap(found -> tenants.inEstablishment(found.establishmentId(), () -> {
            Instant now = clock.instant();
            Optional<BindingRow> binding = store.binding(AccessTokens.hash(bindingToken));
            if (binding.isEmpty() || !usable(binding.get(), now)) {
                return Optional.empty();
            }
            return store.tab(binding.get().tabId()).filter(t -> t.closedAt() == null)
                    .map(t -> new Preview(establishmentName(), t.number(), t.displayName(),
                            binding.get().expiresAt()));
        }));
    }

    /**
     * Confirma o vínculo: consome o QR, cria o único acesso ativo da comanda e o devolve. De duas confirmações
     * simultâneas do mesmo QR, só uma passa (QR travado e consumido na transação). Vazio se o QR não vale.
     */
    public Optional<Bound> confirm(String bindingToken) {
        Optional<BindingRow> found = presentedBinding(bindingToken);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try {
            return tenants.inEstablishment(found.get().establishmentId(), () -> {
                Instant now = clock.instant();
                Optional<BindingRow> binding = store.lockOpenBinding(AccessTokens.hash(bindingToken));
                if (binding.isEmpty() || !usable(binding.get(), now)) {
                    return Optional.<Bound>empty();
                }
                UUID tabId = binding.get().tabId();
                Optional<TabRow> tab = store.tab(tabId).filter(t -> t.closedAt() == null);
                if (tab.isEmpty() || store.activeGrant(tabId).isPresent()) {
                    return Optional.<Bound>empty();
                }
                store.consumeBinding(binding.get().id(), now);
                String grantToken = AccessTokens.generate();
                UUID grantId = UUID.randomUUID();
                store.insertGrant(grantId, tabId, binding.get().id(), AccessTokens.hash(grantToken), now);
                store.audit(binding.get().recovery() ? "acesso.revinculado" : "acesso.vinculado", tabId, null, null,
                        grantId.toString());
                log.info("Consumidor vinculado à comanda {} (acesso {})", tabId, grantId);
                changes.publishInCurrentTransaction(Topic.COMANDA, tabId);
                return Optional.of(new Bound(grantToken, guestTab(store.tab(tabId).orElseThrow())));
            });
        } catch (DuplicateKeyException concurrent) {
            // Outro vínculo da mesma comanda gravou antes: um único acesso ativo por comanda.
            return Optional.empty();
        }
    }

    /** Conexão em tempo real do consumidor (H17): a unidade e a comanda de um acesso válido. */
    public record GuestStream(String grantToken, UUID establishmentId, UUID tabId) {
    }

    /**
     * O acesso guardado no navegador que vale agora para a comanda pedida, com a unidade dela, para abrir a conexão em
     * tempo real filtrada por essa comanda (H17). Vazio sem acesso válido.
     */
    public Optional<GuestStream> stream(List<String> grantTokens, UUID tabId) {
        for (String token : grantTokens) {
            if (!AccessTokens.wellFormed(token)) {
                continue;
            }
            String hash = AccessTokens.hash(token);
            Optional<GrantRow> grant = tenants.forGuestGrant(hash, () -> store.grant(hash))
                    .filter(g -> g.tabId().equals(tabId));
            if (grant.isPresent() && valid(token).filter(t -> t.tabId().equals(tabId)).isPresent()) {
                return Optional.of(new GuestStream(token, grant.get().establishmentId(), tabId));
            }
        }
        return Optional.empty();
    }

    /**
     * Comanda de um acesso válido do consumidor, dentro da transação da unidade (H21). {@code grantId}: o acesso, para
     * a autoria do que o consumidor registra; {@code closed}: a comanda já foi encerrada.
     */
    public record GuestContext(UUID establishmentId, UUID tabId, UUID grantId, boolean closed) {
    }

    /**
     * Roda o trabalho no contexto da unidade da comanda, se um dos acessos guardados no navegador vale para ela; a
     * validade é conferida na mesma transação do trabalho. Vazio sem acesso válido (cliente de outra comanda, acesso
     * revogado ou vencido).
     */
    public <T> Optional<T> inTab(List<String> grantTokens, UUID tabId, java.util.function.Function<GuestContext, T> work) {
        return inTab(grantTokens, tabId, tenants::inEstablishment, work);
    }

    /**
     * Como {@link #inTab}, com a conta Google do consumidor também no contexto (H27): vincular a comanda à conta exige o
     * acesso válido à comanda conferido na mesma transação em que o vínculo é gravado.
     */
    public <T> Optional<T> inTabAsConsumer(List<String> grantTokens, UUID tabId, UUID consumerAccountId,
            java.util.function.Function<GuestContext, T> work) {
        return inTab(grantTokens, tabId, new UnitTransaction() {
            @Override
            public <R> R run(UUID establishmentId, java.util.function.Supplier<R> job) {
                return tenants.inEstablishmentAsConsumer(establishmentId, consumerAccountId, job);
            }
        }, work);
    }

    private interface UnitTransaction {
        <R> R run(UUID establishmentId, java.util.function.Supplier<R> work);
    }

    private <T> Optional<T> inTab(List<String> grantTokens, UUID tabId, UnitTransaction transaction,
            java.util.function.Function<GuestContext, T> work) {
        for (String token : grantTokens) {
            if (!AccessTokens.wellFormed(token)) {
                continue;
            }
            String hash = AccessTokens.hash(token);
            Optional<GrantRow> found = tenants.forGuestGrant(hash, () -> store.grant(hash))
                    .filter(g -> g.tabId().equals(tabId));
            if (found.isEmpty()) {
                continue;
            }
            Optional<T> result = transaction.run(found.get().establishmentId(), () -> {
                Optional<GrantRow> grant = store.grant(hash).filter(g -> g.tabId().equals(tabId));
                Optional<TabRow> tab = store.tab(tabId);
                if (grant.isEmpty() || tab.isEmpty() || !GuestAccessRules.grantValid(grant.get().revokedAt(),
                        tab.get().accessUntil(), clock.instant())) {
                    return Optional.<T>empty();
                }
                return Optional.ofNullable(work.apply(new GuestContext(grant.get().establishmentId(), tabId,
                        grant.get().id(), tab.get().closedAt() != null)));
            });
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    /** Comandas dos acessos guardados no navegador que ainda valem; os que não valem ficam de fora. */
    public List<Remembered> remembered(List<String> grantTokens) {
        List<Remembered> result = new ArrayList<>();
        for (String token : grantTokens) {
            valid(token).ifPresent(tab -> result.add(new Remembered(token, tab)));
        }
        return result;
    }

    /** A comanda de um acesso válido. */
    public Optional<GuestTab> valid(String grantToken) {
        if (!AccessTokens.wellFormed(grantToken)) {
            return Optional.empty();
        }
        String hash = AccessTokens.hash(grantToken);
        Optional<GrantRow> found = tenants.forGuestGrant(hash, () -> store.grant(hash));
        return found.flatMap(g -> tenants.inEstablishment(g.establishmentId(), () -> {
            Optional<GrantRow> grant = store.grant(hash);
            if (grant.isEmpty()) {
                return Optional.<GuestTab>empty();
            }
            Instant now = clock.instant();
            return store.tab(grant.get().tabId())
                    .filter(t -> GuestAccessRules.grantValid(grant.get().revokedAt(), t.accessUntil(), now))
                    .map(this::guestTab);
        }));
    }

    private Optional<BindingRow> presentedBinding(String token) {
        if (!AccessTokens.wellFormed(token)) {
            return Optional.empty();
        }
        String hash = AccessTokens.hash(token);
        return tenants.forBindingToken(hash, () -> store.binding(hash));
    }

    private static boolean usable(BindingRow binding, Instant now) {
        return GuestAccessRules.bindingUsable(binding.expiresAt(), binding.consumedAt(), binding.invalidatedAt(), now);
    }

    private String establishmentName() {
        return units.inCurrentUnit().map(UnitProfiles.Profile::name).orElse("");
    }

    private GuestTab guestTab(TabRow row) {
        return new GuestTab(row.id(), establishmentName(), row.number(), row.displayName(), row.tableLabel(),
                row.openedAt(), row.closedAt() == null);
    }
}
