package tech.malyah.fastpay.identity.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import tech.malyah.fastpay.identity.domain.AttemptLimit;

/**
 * Login Google do consumidor ({@code fastpay.consumer.*}, H27). Endereços do Google por padrão; os testes e a
 * homologação podem apontar outro provedor OpenID. Sem {@code client-id}, o botão "Entrar com Google" responde que o
 * login não está disponível e o avulso continua. Limites aprovados na T-03 da H27 (07/11/2026).
 *
 * @param google               cliente OAuth do Google (segredos só no ambiente, nunca no Git)
 * @param oauthTtl             validade do pedido ao Google (state, nonce e PKCE de uso único) e da etapa do aviso
 * @param googleStartsPerClient logins Google iniciados por origem na janela antes de recusar
 * @param googleStartWindow    janela dos logins iniciados
 * @param privacyNoticeVersion versão do aviso de privacidade que a pessoa aceita ao criar a conta
 */
@ConfigurationProperties("fastpay.consumer")
public record ConsumerProperties(
        @DefaultValue Google google,
        @DefaultValue("10m") Duration oauthTtl,
        @DefaultValue("20") int googleStartsPerClient,
        @DefaultValue("15m") Duration googleStartWindow,
        @DefaultValue("2026-11-07") String privacyNoticeVersion) {

    /**
     * @param clientId         id do cliente OAuth ("Aplicativo da Web"); vazio desliga o login Google
     * @param clientSecret     segredo do cliente
     * @param redirectUri      endereço de retorno cadastrado no Google ({@code /api/v1/consumer/google/callback})
     * @param authorizationUri onde a pessoa entra no Google
     * @param tokenUri         troca do código pelo ID token
     * @param jwkSetUri        chaves públicas que assinam o ID token
     * @param issuer           emissor esperado no ID token (o Google também usa a forma sem {@code https://})
     * @param timeout          prazo de cada chamada ao Google
     */
    public record Google(
            String clientId,
            String clientSecret,
            URI redirectUri,
            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") URI authorizationUri,
            @DefaultValue("https://oauth2.googleapis.com/token") URI tokenUri,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") URI jwkSetUri,
            @DefaultValue("https://accounts.google.com") String issuer,
            @DefaultValue("10s") Duration timeout) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank()
                    && redirectUri != null && redirectUri.isAbsolute();
        }
    }

    public ConsumerProperties {
        if (oauthTtl.isNegative() || oauthTtl.isZero() || oauthTtl.toMinutes() > 30) {
            throw new IllegalArgumentException("fastpay.consumer.oauth-ttl deve estar entre 1 s e 30 min");
        }
    }

    public AttemptLimit googleStartsPerClientLimit() {
        return new AttemptLimit(googleStartsPerClient, googleStartWindow);
    }
}
