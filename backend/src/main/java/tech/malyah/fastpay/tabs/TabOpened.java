package tech.malyah.fastpay.tabs;

import java.time.Instant;
import java.util.UUID;

/**
 * Comanda aberta (H26), publicada na mesma transação da abertura: quem escuta (a conta) lança o couvert automático da
 * versão capturada no mesmo commit (FR-29, FR-68). {@code actorId}: quem abriu.
 */
public record TabOpened(UUID tabId, UUID settingsVersionId, UUID actorId, Instant at) {
}
