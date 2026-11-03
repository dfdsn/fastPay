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

    GuestAccess(TenantTransactions tenants, JdbcTabStore store, UnitProfiles units, Clock clock) {
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
                return Optional.of(new Bound(grantToken, guestTab(store.tab(tabId).orElseThrow())));
            });
        } catch (DuplicateKeyException concurrent) {
            // Outro vínculo da mesma comanda gravou antes: um único acesso ativo por comanda.
            return Optional.empty();
        }
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
