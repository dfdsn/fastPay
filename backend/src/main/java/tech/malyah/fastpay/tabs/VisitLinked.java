package tech.malyah.fastpay.tabs;

import java.time.Instant;
import java.util.UUID;

/**
 * Comanda vinculada à visita de uma comanda anterior (H26, FR-12), publicada na mesma transação do vínculo, com a
 * comanda e a visita de destino já travadas: quem escuta (a conta) aplica "couvert não se repete na visita" no mesmo
 * commit. {@code actorId}: quem vinculou.
 */
public record VisitLinked(UUID tabId, UUID previousTabId, UUID visitId, UUID actorId, Instant at) {
}
