package tech.malyah.fastpay.platform.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Suporte autorizado ({@code fastpay.support.*}, H11, FR-66).
 *
 * @param maxDuration prazo máximo de uma concessão do dono (proposta P-16)
 */
@ConfigurationProperties("fastpay.support")
public record SupportProperties(@DefaultValue("72h") Duration maxDuration) {

    public SupportProperties {
        if (maxDuration.toHours() < 1) {
            throw new IllegalArgumentException("fastpay.support.max-duration deve ser de pelo menos 1 hora");
        }
    }
}
