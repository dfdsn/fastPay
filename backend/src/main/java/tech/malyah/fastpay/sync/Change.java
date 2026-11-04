package tech.malyah.fastpay.sync;

import java.util.UUID;

/** Aviso de mudança na unidade, na posição {@code seq} da sequência dela. {@code tabId} vazio: não é de uma comanda. */
public record Change(long seq, Topic topic, UUID tabId) {
}
