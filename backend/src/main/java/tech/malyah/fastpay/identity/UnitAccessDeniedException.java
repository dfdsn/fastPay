package tech.malyah.fastpay.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * A identidade não tem vínculo ativo na unidade pedida. A resposta ao cliente é a mesma para unidade
 * inexistente, alheia ou com vínculo desativado, para não revelar existência.
 */
public class UnitAccessDeniedException extends RuntimeException {

    private final UUID establishmentId;

    public UnitAccessDeniedException(UUID establishmentId) {
        super("Sem vínculo ativo na unidade.");
        this.establishmentId = establishmentId;
    }

    /** Unidade recusada, para uso interno (ex.: esquecer a escolha da sessão); nunca vai na resposta. */
    public Optional<UUID> establishmentId() {
        return Optional.ofNullable(establishmentId);
    }
}
