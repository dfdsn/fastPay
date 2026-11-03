package tech.malyah.fastpay.tabs.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Comandas e acesso do consumidor (H15). {@code bindingQrTtl}: validade do QR de vínculo (2 min, D-17).
 * {@code guestCookieMaxAge}: quanto o navegador guarda o acesso; a validade real é conferida no servidor.
 * {@code guestCookieMaxTabs}: comandas lembradas no mesmo navegador (as mais antigas saem primeiro).
 */
@ConfigurationProperties("fastpay.tabs")
public record TabsProperties(
        @DefaultValue("2m") Duration bindingQrTtl,
        @DefaultValue("180d") Duration guestCookieMaxAge,
        @DefaultValue("10") int guestCookieMaxTabs) {
}
