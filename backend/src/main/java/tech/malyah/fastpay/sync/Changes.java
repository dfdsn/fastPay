package tech.malyah.fastpay.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tech.malyah.fastpay.sync.infrastructure.JdbcSyncStore;
import tech.malyah.fastpay.sync.infrastructure.SyncProperties;
import tech.malyah.fastpay.tenancy.TenantTransactions;

/**
 * Avisos de mudança da unidade (H17): gravar junto com o fato e ler depois de um cursor. O cursor é a posição na
 * sequência densa da unidade (V14), em texto para o cliente.
 */
@Service
public class Changes {

    /** A cada tantas publicações a retenção apaga avisos antigos da unidade. */
    static final long RETENTION_EVERY = 200;

    private final JdbcSyncStore store;
    private final TenantTransactions tenants;
    private final SyncProperties properties;
    private final Clock clock;

    Changes(JdbcSyncStore store, TenantTransactions tenants, SyncProperties properties, Clock clock) {
        this.store = store;
        this.tenants = tenants;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Grava o aviso na transação corrente, que precisa ter o contexto da unidade: commit do fato e do aviso juntos.
     * Chamar no fim do trabalho, depois das outras travas, para segurar a sequência da unidade o mínimo possível.
     */
    public void publishInCurrentTransaction(Topic topic, UUID tabId) {
        Objects.requireNonNull(topic, "topic");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Aviso de mudança exige a transação do fato.");
        }
        Instant now = clock.instant();
        long seq = store.next();
        store.insert(seq, topic, tabId, now);
        if (seq % RETENTION_EVERY == 0) {
            store.deleteBefore(now.minus(properties.retention()));
        }
    }

    /** Avisos visíveis depois do cursor (texto do cliente; inválido ou ausente vale como perdido). */
    public Batch read(UUID establishmentId, String after, Predicate<Change> visible) {
        Batch all = read(establishmentId, parse(after));
        return all.resync() ? all : new Batch(all.cursor(), false, all.changes().stream().filter(visible).toList());
    }

    /** Todos os avisos depois do cursor, ou pedido de leitura completa se o cursor não serve. */
    Batch read(UUID establishmentId, Long after) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        return tenants.inEstablishment(establishmentId, () -> {
            long last = store.last();
            if (after == null || after > last) {
                return Batch.resyncAt(last);
            }
            if (after == last) {
                return new Batch(last, false, List.of());
            }
            if (last - after > properties.maxReplay()) {
                return Batch.resyncAt(last);
            }
            List<Change> changes = store.between(after, last);
            // Sequência densa: faltar posição só acontece quando a retenção apagou o começo.
            return changes.size() == last - after ? new Batch(last, false, changes) : Batch.resyncAt(last);
        });
    }

    /** Cursor do cliente: posição não negativa; qualquer outra coisa vale como ausente. */
    static Long parse(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > 18 || !cursor.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(cursor);
    }
}
