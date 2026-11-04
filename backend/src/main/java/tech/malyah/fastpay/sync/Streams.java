package tech.malyah.fastpay.sync;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tech.malyah.fastpay.sync.infrastructure.SyncProperties;

/**
 * Conexões SSE abertas (H17, especificação §11). Cada conexão é de um contexto (sessão da equipe numa unidade, ou acesso
 * do consumidor a uma comanda), com filtro do que ela pode ver e uma reconferência do contexto. Um único envio periódico
 * ({@link #dispatch()}) lê os avisos novos de cada unidade com conexões e manda a cada uma o que ela vê; também reconfere
 * o contexto, manda heartbeat e pede reconexão ao fim da duração máxima. Eventos:
 * <ul>
 * <li>{@code ready} (ao abrir): {@code {"cursor","resync"}}; {@code resync=true} manda reler tudo pela API;</li>
 * <li>{@code change}: {@code {"cursor","topic","tabId"}}, com o cursor também no {@code id} do evento;</li>
 * <li>{@code resync}: atrás demais; reler tudo e seguir do {@code cursor};</li>
 * <li>{@code heartbeat}: {@code {"cursor"}}, sem avisos no intervalo;</li>
 * <li>{@code closed}: {@code {"reason"}} e fim da conexão: {@code RECONECTAR} (duração máxima) ou o motivo da
 * reconferência (sessão encerrada, unidade não autorizada, acesso encerrado).</li>
 * </ul>
 * Nenhum evento leva dados da comanda: só que mudou (NFR-02).
 */
@Service
public class Streams {

    public static final String RECONNECT = "RECONECTAR";

    private static final Logger log = LoggerFactory.getLogger(Streams.class);

    /**
     * Pedido de conexão. {@code context}: identifica a sessão ou o acesso, para o limite de conexões simultâneas.
     * {@code after}: cursor do cliente. {@code visible}: o que esta conexão pode receber. {@code recheck}: vazio enquanto o
     * contexto vale; senão, o motivo para encerrar.
     */
    public record Subscription(UUID establishmentId, String context, String after, Predicate<Change> visible,
            Supplier<Optional<String>> recheck) {

        public Subscription {
            Objects.requireNonNull(establishmentId, "establishmentId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(visible, "visible");
            Objects.requireNonNull(recheck, "recheck");
        }
    }

    private final Changes changes;
    private final SyncProperties properties;
    private final Clock clock;
    private final Map<UUID, Set<Subscriber>> byUnit = new ConcurrentHashMap<>();

    Streams(Changes changes, SyncProperties properties, Clock clock) {
        this.changes = changes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Abre a conexão: manda {@code ready} com o cursor de onde ela segue; o envio periódico manda o que veio depois.
     *
     * @throws TooManyStreamsException conexões demais no contexto ou no servidor
     */
    public SseEmitter open(Subscription subscription) {
        if (count() >= properties.maxStreams() || count(subscription.context()) >= properties.maxStreamsPerContext()) {
            throw new TooManyStreamsException();
        }
        Instant now = clock.instant();
        Batch start = changes.read(subscription.establishmentId(), Changes.parse(subscription.after()));
        // Com cursor válido, a reconexão recebe só o que perdeu (replay); sem ele, relê tudo e segue da última posição.
        long cursor = start.resync() ? start.cursor() : Changes.parse(subscription.after());
        // A duração máxima é controlada no envio periódico; o prazo do emitter é só a rede de segurança.
        SseEmitter emitter = new SseEmitter(properties.streamLifetime().plus(properties.dispatchInterval())
                .plusMinutes(1).toMillis());
        Subscriber subscriber = new Subscriber(subscription, emitter, cursor, now);
        if (!subscriber.send("ready", null, "{\"cursor\":\"%d\",\"resync\":%s}".formatted(start.cursor(), start.resync()))) {
            return emitter;
        }
        Set<Subscriber> unit = byUnit.computeIfAbsent(subscription.establishmentId(), id -> ConcurrentHashMap.newKeySet());
        unit.add(subscriber);
        emitter.onCompletion(() -> remove(subscriber));
        emitter.onTimeout(() -> remove(subscriber));
        emitter.onError(error -> remove(subscriber));
        return emitter;
    }

    /** Desligamento (deploy, restart): pede a todas as conexões que reconectem, com o cursor que têm. */
    @PreDestroy
    void closeAll() {
        byUnit.values().stream().flatMap(Set::stream).toList().forEach(s -> s.close(RECONNECT));
    }

    /** Conexões abertas agora. */
    public int count() {
        return byUnit.values().stream().mapToInt(Set::size).sum();
    }

    int count(String context) {
        return (int) byUnit.values().stream().flatMap(Set::stream)
                .filter(s -> s.subscription.context().equals(context)).count();
    }

    /** Envio periódico: avisos novos, reconferência, heartbeat e duração máxima. */
    public void dispatch() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, Set<Subscriber>> unit : byUnit.entrySet()) {
            Map<Long, Batch> reads = new HashMap<>();
            for (Subscriber subscriber : unit.getValue()) {
                try {
                    dispatch(unit.getKey(), subscriber, now, reads);
                } catch (RuntimeException failure) {
                    // Banco indisponível, por exemplo: a conexão fica e a próxima rodada tenta de novo.
                    log.warn("Falha ao atualizar uma conexão da unidade {}: {}", unit.getKey(),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    private void dispatch(UUID establishmentId, Subscriber subscriber, Instant now, Map<Long, Batch> reads) {
        boolean lifetimeOver = !now.isBefore(subscriber.openedAt.plus(properties.streamLifetime()));
        // Ao fim da duração máxima também reconfere: sessão vencida encerra em vez de pedir reconexão.
        if (lifetimeOver || !now.isBefore(subscriber.checkedAt.plus(properties.recheckInterval()))) {
            Optional<String> ended = subscriber.subscription.recheck().get();
            subscriber.checkedAt = now;
            if (ended.isPresent()) {
                subscriber.close(ended.get());
                return;
            }
        }
        if (lifetimeOver) {
            subscriber.close(RECONNECT);
            return;
        }
        Batch batch = reads.computeIfAbsent(subscriber.cursor, after -> changes.read(establishmentId, after));
        if (batch.resync()) {
            subscriber.cursor = batch.cursor();
            subscriber.send("resync", null, cursorJson(batch.cursor()));
            subscriber.sentAt = now;
            return;
        }
        for (Change change : batch.changes()) {
            if (subscriber.subscription.visible().test(change)) {
                if (!subscriber.send("change", Long.toString(change.seq()), changeJson(change))) {
                    return;
                }
                subscriber.sentAt = now;
            }
        }
        subscriber.cursor = batch.cursor();
        if (!now.isBefore(subscriber.sentAt.plus(properties.heartbeatInterval()))) {
            subscriber.send("heartbeat", null, cursorJson(batch.cursor()));
            subscriber.sentAt = now;
        }
    }

    private void remove(Subscriber subscriber) {
        Set<Subscriber> unit = byUnit.get(subscriber.subscription.establishmentId());
        if (unit != null) {
            unit.remove(subscriber);
            if (unit.isEmpty()) {
                byUnit.remove(subscriber.subscription.establishmentId(), unit);
            }
        }
    }

    private static String cursorJson(long cursor) {
        return "{\"cursor\":\"%d\"}".formatted(cursor);
    }

    private static String changeJson(Change change) {
        return "{\"cursor\":\"%d\",\"topic\":\"%s\",\"tabId\":%s}".formatted(change.seq(), change.topic().name(),
                change.tabId() == null ? "null" : "\"" + change.tabId() + "\"");
    }

    /** Uma conexão. Só o envio periódico mexe no cursor e nos instantes depois de aberta. */
    private final class Subscriber {

        private final Subscription subscription;
        private final SseEmitter emitter;
        private final Instant openedAt;
        private long cursor;
        private Instant checkedAt;
        private Instant sentAt;

        private Subscriber(Subscription subscription, SseEmitter emitter, long cursor, Instant now) {
            this.subscription = subscription;
            this.emitter = emitter;
            this.cursor = cursor;
            this.openedAt = now;
            this.checkedAt = now;
            this.sentAt = now;
        }

        /** {@code false} se a conexão caiu (e já foi retirada). */
        private boolean send(String name, String id, String json) {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON);
            if (id != null) {
                event.id(id);
            }
            try {
                emitter.send(event);
                return true;
            } catch (IOException | IllegalStateException gone) {
                remove(this);
                emitter.complete();
                return false;
            }
        }

        private void close(String reason) {
            send("closed", null, "{\"reason\":\"%s\"}".formatted(reason));
            remove(this);
            emitter.complete();
        }
    }
}
