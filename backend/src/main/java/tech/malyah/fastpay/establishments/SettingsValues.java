package tech.malyah.fastpay.establishments;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Valores de uma versão da configuração comercial e operacional da unidade (H14), já validados
 * ({@code establishments.domain.SettingsRules}).
 *
 * <ul>
 * <li>Serviço (FR-28): habilitado, percentual com duas casas, base antes ou depois do desconto e se o couvert entra
 * na base. Capturado na abertura da comanda.
 * <li>Couvert (FR-29): habilitado, automático (capturado na abertura) ou manual (capturado na inclusão) e valor por
 * pessoa; {@code couvertAmount} nulo só com o couvert desligado.
 * <li>Desconto (FR-30): formatos aceitos, em reais e/ou percentual; conferidos no momento de conceder.
 * <li>Atendimento (FR-23) e saída (FR-40): capturados na abertura da comanda.
 * </ul>
 */
public record SettingsValues(boolean serviceEnabled, BigDecimal servicePercent, ServiceBase serviceBase,
        boolean serviceIncludesCouvert, boolean couvertEnabled, CouvertMode couvertMode, BigDecimal couvertAmount,
        boolean discountFixedAllowed, boolean discountPercentAllowed, AttendanceMode attendanceMode,
        ExitMode exitMode) {

    public SettingsValues {
        Objects.requireNonNull(servicePercent, "servicePercent");
        Objects.requireNonNull(serviceBase, "serviceBase");
        Objects.requireNonNull(couvertMode, "couvertMode");
        Objects.requireNonNull(attendanceMode, "attendanceMode");
        Objects.requireNonNull(exitMode, "exitMode");
    }
}
