package tech.malyah.fastpay.identity.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tech.malyah.fastpay.identity.ConsumerSession;
import tech.malyah.fastpay.identity.domain.SessionWindow;

/**
 * Prazos da sessão do consumidor com Google (H27, especificação §5): 7 dias sem ação da pessoa e 30 dias no total.
 * Vencida, a sessão é encerrada e a requisição segue sem consumidor ({@code CONSUMIDOR_SEM_LOGIN}); o acesso avulso
 * da comanda, que é outro cookie, não muda. Avisos em tempo real e polling da comanda não contam como ação.
 */
@Component
class ConsumerSessionFilter extends OncePerRequestFilter implements Ordered {

    private final ConsumerSession session;
    private final Clock clock;

    ConsumerSessionFilter(ConsumerSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** Logo depois do filtro dos prazos da equipe. */
    @Override
    public int getOrder() {
        return SessionRepositoryFilter.DEFAULT_ORDER + 11;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (session.account(request).isPresent()) {
            Instant now = clock.instant();
            Optional<SessionWindow> window = session.window(request);
            if (window.isEmpty() || window.get().expiredAt(now)) {
                session.signOut(request);
            } else if (!background(request)) {
                session.recordActivity(request, window.get().touchedAt(now));
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean background(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "1".equals(request.getHeader(SessionExpiryFilter.BACKGROUND_HEADER))
                || (uri.startsWith("/api/v1/guest/") && (uri.endsWith("/stream") || uri.endsWith("/changes")));
    }
}
