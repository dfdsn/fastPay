package tech.malyah.fastpay.identity.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import tech.malyah.fastpay.identity.domain.AttemptLimit;

/**
 * Configuração técnica do login e da redefinição de senha ({@code fastpay.auth.*}, H08). Os valores
 * padrão são os aprovados em P-07/T-03 (12/10/2026); os prazos de sessão por perfil e
 * os 30 minutos do link de redefinição são regra da especificação §5 e não ficam aqui.
 *
 * @param passwordMinLength        comprimento mínimo da senha (8..64)
 * @param bcryptStrength           custo do bcrypt (10..16)
 * @param resetLinkBase            endereço da tela de redefinição; o token vai no fragmento ({@code #token=})
 * @param loginFailuresPerAccount  falhas de login por e-mail na janela antes de bloquear
 * @param loginFailuresPerClient   falhas de login por endereço de origem na janela antes de bloquear
 * @param loginWindow              janela das falhas de login
 * @param resetRequestsPerAccount  pedidos de redefinição por e-mail na janela; acima disso não envia e-mail
 * @param resetRequestsPerClient   pedidos de redefinição por origem na janela antes de recusar
 * @param resetFailuresPerClient   links inválidos apresentados por origem na janela antes de recusar
 * @param resetWindow              janela dos pedidos e falhas de redefinição
 * @param sessionCookieSecure      cookie de sessão só por HTTPS; desligar apenas em dev sem HTTPS
 */
@ConfigurationProperties("fastpay.auth")
public record AuthProperties(
        @DefaultValue("12") int passwordMinLength,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue("http://127.0.0.1:8088/redefinir-senha") String resetLinkBase,
        @DefaultValue("5") int loginFailuresPerAccount,
        @DefaultValue("20") int loginFailuresPerClient,
        @DefaultValue("15m") Duration loginWindow,
        @DefaultValue("3") int resetRequestsPerAccount,
        @DefaultValue("10") int resetRequestsPerClient,
        @DefaultValue("10") int resetFailuresPerClient,
        @DefaultValue("1h") Duration resetWindow,
        @DefaultValue("true") boolean sessionCookieSecure) {

    public AuthProperties {
        if (bcryptStrength < 10 || bcryptStrength > 16) {
            throw new IllegalArgumentException("fastpay.auth.bcrypt-strength deve estar entre 10 e 16");
        }
        if (resetLinkBase == null || resetLinkBase.isBlank() || resetLinkBase.contains("#")) {
            throw new IllegalArgumentException("fastpay.auth.reset-link-base deve ser um endereço sem fragmento");
        }
    }

    public AttemptLimit loginPerAccount() {
        return new AttemptLimit(loginFailuresPerAccount, loginWindow);
    }

    public AttemptLimit loginPerClient() {
        return new AttemptLimit(loginFailuresPerClient, loginWindow);
    }

    public AttemptLimit resetRequestPerAccount() {
        return new AttemptLimit(resetRequestsPerAccount, resetWindow);
    }

    public AttemptLimit resetRequestPerClient() {
        return new AttemptLimit(resetRequestsPerClient, resetWindow);
    }

    public AttemptLimit resetFailurePerClient() {
        return new AttemptLimit(resetFailuresPerClient, resetWindow);
    }
}
