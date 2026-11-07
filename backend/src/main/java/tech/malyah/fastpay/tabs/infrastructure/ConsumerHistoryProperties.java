package tech.malyah.fastpay.tabs.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Histórico do consumidor com Google (H27, T-03 aprovada em 07/11/2026): {@code pageSize} comandas por página quando a
 * tela não pede outro valor, e no máximo {@code maxPageSize} (D-13: 100 por página).
 */
@ConfigurationProperties("fastpay.consumer.history")
public record ConsumerHistoryProperties(
        @DefaultValue("20") int pageSize,
        @DefaultValue("100") int maxPageSize) {

    public ConsumerHistoryProperties {
        if (pageSize < 1 || maxPageSize < pageSize || maxPageSize > 100) {
            throw new IllegalArgumentException("fastpay.consumer.history: 1 <= page-size <= max-page-size <= 100");
        }
    }
}
