package tech.malyah.fastpay.identity.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

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
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Aplica os prazos da sessão autenticada (especificação §5) antes de qualquer operação: inatividade e
 * duração absoluta pelo perfil. Sessão vencida é encerrada e a requisição segue sem identidade, o que
 * resulta em 401 {@code SESSAO_AUSENTE}. Só ação da pessoa renova a inatividade: requisições marcadas
 * com {@value #BACKGROUND_HEADER} (polling, heartbeat, SSE) não contam.
 */
@Component
class SessionExpiryFilter extends OncePerRequestFilter implements Ordered {

    static final String BACKGROUND_HEADER = "X-Fastpay-Background";

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryFilter.class);

    private final SessionContext session;
    private final Clock clock;

    SessionExpiryFilter(SessionContext session, Clock clock) {
        this.session = session;
        this.clock = clock;
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
            } else if (!"1".equals(request.getHeader(BACKGROUND_HEADER))) {
                session.recordActivity(request, window.get().touchedAt(now));
            }
        }
        chain.doFilter(request, response);
    }
}
