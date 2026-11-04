package tech.malyah.fastpay.sync;

import java.util.List;

/**
 * Avisos depois de um cursor, até {@code cursor} (a última posição da unidade na leitura). {@code resync}: o cursor
 * informado não serve (ausente, perdido pela retenção, de outra sequência ou atrás demais) e quem lê precisa reler tudo
 * pela API; nesse caso {@code changes} vem vazio.
 */
public record Batch(long cursor, boolean resync, List<Change> changes) {

    public Batch {
        changes = List.copyOf(changes);
    }

    static Batch resyncAt(long cursor) {
        return new Batch(cursor, true, List.of());
    }
}
