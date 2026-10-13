package tech.malyah.fastpay.identity.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tech.malyah.fastpay.identity.SessionContext;
import tech.malyah.fastpay.identity.application.MultiFactor;
import tech.malyah.fastpay.identity.domain.AuthStage;
import tech.malyah.fastpay.identity.infrastructure.AuthProperties;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Aplica os prazos da sessão autenticada (especificação §5) antes de qualquer operação: inatividade e
 * duração absoluta pelo perfil. Sessão vencida é encerrada e a requisição segue sem identidade, o que
 * resulta em 401 {@code SESSAO_AUSENTE}. Só ação da pessoa renova a inatividade: requisições marcadas
 * com {@value #BACKGROUND_HEADER} (polling, heartbeat, SSE) não contam. Sessão parada na etapa do MFA
 * (H09) vale só pelo prazo curto de {@code fastpay.auth.mfa-pending-timeout}.
 */
@Component
class SessionExpiryFilter extends OncePerRequestFilter implements Ordered {

    static final String BACKGROUND_HEADER = "X-Fastpay-Background";

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryFilter.class);

    private final SessionContext session;
    private final MultiFactor mfa;
    private final AuthProperties properties;
    private final Clock clock;

    SessionExpiryFilter(SessionContext session, MultiFactor mfa, AuthProperties properties, Clock clock) {
        this.session = session;
        this.mfa = mfa;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sessão autenticada sem segundo fator, de alguém que agora precisa dele (promovido a gerente, admin
     * do estabelecimento ou admin fastPay, ou que ativou o MFA em outra sessão): volta para a etapa do MFA
     * antes de qualquer função (FR-05, FR-06). Só estas sessões pagam a consulta.
     */
    private void requireMfaIfNowNeeded(HttpServletRequest request, Instant now) {
        if (session.mfaVerified(request)) {
            return;
        }
        UUID identityId = session.requireIdentity(request);
        MultiFactor.Requirement requirement = mfa.requirement(identityId);
        if (requirement.enrolled() || requirement.required()) {
            AuthStage stage = requirement.enrolled() ? AuthStage.MFA_PENDENTE : AuthStage.MFA_CADASTRO_OBRIGATORIO;
            session.beginPending(request, identityId, stage, now, properties.mfaPendingTimeout());
            log.info("Sessão da identidade {} voltou para a etapa {}", identityId, stage);
        }
    }

    /** Logo depois do Spring Session, antes do Spring Security e dos controllers. */
    @Override
    public int getOrder() {
        return SessionRepositoryFilter.DEFAULT_ORDER + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (session.hasIdentity(request)) {
            Instant now = clock.instant();
            Optional<SessionWindow> window = session.window(request);
            if (window.isEmpty() || window.get().expiredAt(now)) {
                session.signOut(request);
                log.debug("Sessão encerrada por prazo vencido");
            } else {
                if (!"1".equals(request.getHeader(BACKGROUND_HEADER))) {
                    session.recordActivity(request, window.get().touchedAt(now));
                }
                requireMfaIfNowNeeded(request, now);
            }
        } else {
            session.pending(request)
                    .filter(p -> !clock.instant().isBefore(p.since().plus(properties.mfaPendingTimeout())))
                    .ifPresent(p -> session.signOut(request));
        }
        chain.doFilter(request, response);
    }
}
