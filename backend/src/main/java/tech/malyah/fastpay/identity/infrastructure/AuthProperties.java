package tech.malyah.fastpay.identity.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import tech.malyah.fastpay.identity.domain.AttemptLimit;

/**
 * Configuração técnica do login, da redefinição de senha e do MFA ({@code fastpay.auth.*}, H08/H09). Os valores
 * padrão são os aprovados em P-07/T-03 (12/10/2026); os prazos de sessão por perfil e
 * os 30 minutos do link de redefinição são regra da especificação §5 e não ficam aqui.
 *
 * @param passwordMinLength        comprimento mínimo da senha (8..64)
 * @param bcryptStrength           custo do bcrypt (10..16)
 * @param loginFailuresPerAccount  falhas de login por e-mail na janela antes de bloquear
 * @param loginFailuresPerClient   falhas de login por endereço de origem na janela antes de bloquear
 * @param loginWindow              janela das falhas de login
 * @param resetRequestsPerAccount  pedidos de redefinição por e-mail na janela; acima disso não envia e-mail
 * @param resetRequestsPerClient   pedidos de redefinição por origem na janela antes de recusar
 * @param resetFailuresPerClient   links inválidos apresentados por origem na janela antes de recusar
 * @param resetWindow              janela dos pedidos e falhas de redefinição
 * @param sessionCookieSecure      cookie de sessão só por HTTPS; desligar apenas em dev sem HTTPS
 * @param mfaEncryptionKey         chave AES-256 (Base64) do segredo TOTP, fora do banco; obrigatória (H09)
 * @param mfaIssuer                nome do emissor mostrado no aplicativo autenticador
 * @param mfaFailuresPerAccount    códigos errados (TOTP ou recuperação) por identidade na janela de login
 * @param mfaPendingTimeout        prazo para concluir a verificação ou o cadastro do MFA depois da senha
 */
@ConfigurationProperties("fastpay.auth")
public record AuthProperties(
        @DefaultValue("12") int passwordMinLength,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue("5") int loginFailuresPerAccount,
        @DefaultValue("20") int loginFailuresPerClient,
        @DefaultValue("15m") Duration loginWindow,
        @DefaultValue("3") int resetRequestsPerAccount,
        @DefaultValue("10") int resetRequestsPerClient,
        @DefaultValue("10") int resetFailuresPerClient,
        @DefaultValue("1h") Duration resetWindow,
        @DefaultValue("true") boolean sessionCookieSecure,
        String mfaEncryptionKey,
        @DefaultValue("fastPay") String mfaIssuer,
        @DefaultValue("5") int mfaFailuresPerAccount,
        @DefaultValue("10m") Duration mfaPendingTimeout) {

    public AuthProperties {
        if (bcryptStrength < 10 || bcryptStrength > 16) {
            throw new IllegalArgumentException("fastpay.auth.bcrypt-strength deve estar entre 10 e 16");
        }
        if (mfaPendingTimeout.isNegative() || mfaPendingTimeout.isZero() || mfaPendingTimeout.toMinutes() > 30) {
            throw new IllegalArgumentException("fastpay.auth.mfa-pending-timeout deve estar entre 1 s e 30 min");
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

    public AttemptLimit mfaPerAccount() {
        return new AttemptLimit(mfaFailuresPerAccount, loginWindow);
    }

    public AttemptLimit resetFailurePerClient() {
        return new AttemptLimit(resetFailuresPerClient, resetWindow);
    }
}
