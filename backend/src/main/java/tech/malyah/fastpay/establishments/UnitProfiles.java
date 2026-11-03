package tech.malyah.fastpay.establishments;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.infrastructure.JdbcEstablishmentStore;

/**
 * Nome e situação da unidade do contexto da transação de quem chama (H15). A comanda confere se a unidade pode
 * operar; a tela do consumidor mostra o nome. Não confere vínculo: quem chama já está numa transação autorizada.
 */
@Service
public class UnitProfiles {

    /** Situação gravada quando o admin fastPay ativa a unidade (H39). Hoje toda unidade está em configuração. */
    static final String ACTIVE = "ATIVA";

    /** {@code active}: liberada para operação produtiva pelo admin fastPay (FR-72, H39). */
    public record Profile(UUID id, String name, boolean active, UUID logoFileId) {
    }

    private final JdbcEstablishmentStore store;

    UnitProfiles(JdbcEstablishmentStore store) {
        this.store = store;
    }

    public Optional<Profile> inCurrentUnit() {
        return store.profile().map(p -> new Profile(p.id(), p.name(), ACTIVE.equals(p.status()), p.logoFileId()));
    }
}
