package tech.malyah.fastpay.tabs.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Comandas e acesso do consumidor (H15). {@code bindingQrTtl}: validade do QR de vínculo (2 min, D-17).
 * {@code guestCookieMaxAge}: quanto o navegador guarda o acesso; a validade real é conferida no servidor.
 * {@code guestCookieMaxTabs}: comandas lembradas no mesmo navegador (as mais antigas saem primeiro).
 * {@code visitContinuationWindow}: até quanto tempo depois da abertura da comanda anterior a nova pode continuar a
 * visita dela (H26, T-03: 24 h; a virada do dia não encerra a visita).
 */
@ConfigurationProperties("fastpay.tabs")
public record TabsProperties(
        @DefaultValue("2m") Duration bindingQrTtl,
        @DefaultValue("180d") Duration guestCookieMaxAge,
        @DefaultValue("10") int guestCookieMaxTabs,
        @DefaultValue("24h") Duration visitContinuationWindow) {
}
