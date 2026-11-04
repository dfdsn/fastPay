package tech.malyah.fastpay.sync;

import java.util.List;
import java.util.Optional;

/** Resposta do polling (H17), no mesmo formato dos eventos SSE; o cursor vai em texto. */
public record Poll(String cursor, boolean resync, List<Item> changes) {

    public record Item(String cursor, String topic, String tabId) {
    }

    public static Poll of(Batch batch) {
        return new Poll(Long.toString(batch.cursor()), batch.resync(), batch.changes().stream()
                .map(c -> new Item(Long.toString(c.seq()), c.topic().name(),
                        Optional.ofNullable(c.tabId()).map(Object::toString).orElse(null)))
                .toList());
    }
}
