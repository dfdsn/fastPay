package tech.malyah.fastpay.sync.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tech.malyah.fastpay.identity.LiveSessions;
import tech.malyah.fastpay.sync.Changes;
import tech.malyah.fastpay.sync.Poll;
import tech.malyah.fastpay.sync.Streams;

/**
 * Avisos de mudança da unidade para a equipe (H17). {@code GET /stream}: SSE (eventos em {@link Streams}), no
 * contexto da sessão e da unidade escolhida, reconferido periodicamente. {@code GET /changes}: o mesmo por polling, para
 * quando o SSE não conecta; o cliente manda {@code X-Fastpay-Background: 1}, que não renova a sessão. Os dois só dizem que
 * algo mudou: a tela relê pela API, que autoriza cada leitura.
 */
@RestController
@RequestMapping("/api/v1/sync")
class SyncController {

    /** Caminho do SSE: o filtro da sessão o trata como segundo plano (EventSource não manda header). */
    static final String STREAM_PATH = "/api/v1/sync/stream";

    private final LiveSessions sessions;
    private final Streams streams;
    private final Changes changes;

    SyncController(LiveSessions sessions, Streams streams, Changes changes) {
        this.sessions = sessions;
        this.streams = streams;
        this.changes = changes;
    }

    @GetMapping("/stream")
    SseEmitter stream(@RequestParam(required = false) String after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletRequest request,
            HttpServletResponse response) {
        LiveSessions.Live live = sessions.current(request);
        noBuffering(response);
        return streams.open(new Streams.Subscription(live.establishmentId(), "equipe:" + live.sessionId(),
                after != null ? after : lastEventId, change -> true,
                () -> sessions.recheck(live).map(Enum::name)));
    }

    @GetMapping("/changes")
    Poll changes(@RequestParam(required = false) String after, HttpServletRequest request) {
        LiveSessions.Live live = sessions.current(request);
        return Poll.of(changes.read(live.establishmentId(), after, change -> true));
    }

    /** Proxy sem buffer (nginx) e nada guardado em cache. */
    private static void noBuffering(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
    }
}
