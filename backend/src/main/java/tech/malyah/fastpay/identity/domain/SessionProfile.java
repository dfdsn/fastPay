package tech.malyah.fastpay.identity.domain;

import java.time.Duration;
import java.util.Collection;

import tech.malyah.fastpay.identity.MembershipRole;

/**
 * Limites de sessão da equipe por perfil (especificação §5, PRD "Sessões da equipe"). A sessão usa o
 * perfil mais restritivo entre os contextos que ela alcança: admin fastPay, depois gestão de loja
 * (admin do estabelecimento ou gerente), depois operação (garçom e produção).
 */
public enum SessionProfile {
    OPERACAO(Duration.ofHours(1), Duration.ofHours(12)),
    GESTAO(Duration.ofMinutes(30), Duration.ofHours(12)),
    PLATAFORMA(Duration.ofMinutes(15), Duration.ofHours(8));

    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    SessionProfile(Duration idleTimeout, Duration absoluteTimeout) {
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    public Duration absoluteTimeout() {
        return absoluteTimeout;
    }

    public static SessionProfile mostRestrictive(boolean platformAdmin, Collection<MembershipRole> activeRoles) {
        if (platformAdmin) {
            return PLATAFORMA;
        }
        if (activeRoles.contains(MembershipRole.ADMIN_ESTABELECIMENTO) || activeRoles.contains(MembershipRole.GERENTE)) {
            return GESTAO;
        }
        return OPERACAO;
    }
}
