package tech.malyah.fastpay.identity;

import tech.malyah.fastpay.identity.domain.AuthStage;

/**
 * A sessão passou pela senha, mas ainda falta a verificação em duas etapas (H09): confirmar o código ou
 * cadastrar o autenticador. Nenhuma função fica acessível até lá.
 */
public class MfaRequiredException extends RuntimeException {

    private final AuthStage stage;

    public MfaRequiredException(AuthStage stage) {
        super("Verificação em duas etapas pendente: " + stage);
        this.stage = stage;
    }

    public AuthStage stage() {
        return stage;
    }
}
