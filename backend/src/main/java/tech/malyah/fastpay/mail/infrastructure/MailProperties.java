package tech.malyah.fastpay.mail.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do e-mail ({@code fastpay.mail.*}, H12). Segredos (senha SMTP) só por variável de ambiente.
 *
 * @param transport          {@code caixa-de-teste} (memória, nenhum envio real) ou {@code smtp}
 * @param from               remetente; no Gmail de homologação, o próprio endereço da conta
 * @param publicBaseUrl      endereço público do frontend usado nos links (sem barra final, consulta ou fragmento)
 * @param allowedRecipients  destinatários permitidos ({@code pessoa@dominio} ou {@code @dominio}); obrigatória
 *                           com SMTP fora de produção
 * @param timeZone           fuso dos horários escritos nas mensagens
 * @param testMailboxEndpoint expõe a caixa de teste pela API; só em desenvolvimento
 * @param smtp               servidor SMTP (STARTTLS)
 */
@ConfigurationProperties("fastpay.mail")
public record MailProperties(
        @DefaultValue("caixa-de-teste") Transport transport,
        @DefaultValue("fastPay <nao-responda@fastpay.invalid>") String from,
        @DefaultValue("http://127.0.0.1:8088") String publicBaseUrl,
        @DefaultValue List<String> allowedRecipients,
        @DefaultValue("America/Sao_Paulo") ZoneId timeZone,
        @DefaultValue("false") boolean testMailboxEndpoint,
        @DefaultValue Smtp smtp) {

    public enum Transport {
        CAIXA_DE_TESTE,
        SMTP
    }

    /**
     * @param timeout limite de conexão, leitura e escrita; o envio roda dentro da transação do job
     */
    public record Smtp(String host, @DefaultValue("587") int port, String username, String password,
            @DefaultValue("true") boolean startTls, @DefaultValue("10s") Duration timeout) {
    }

    public MailProperties {
        URI base;
        try {
            base = URI.create(publicBaseUrl == null ? "" : publicBaseUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("fastpay.mail.public-base-url (PUBLIC_BASE_URL) inválido");
        }
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getRawQuery() != null || base.getRawFragment() != null || publicBaseUrl.endsWith("/")) {
            throw new IllegalArgumentException(
                    "fastpay.mail.public-base-url (PUBLIC_BASE_URL) deve ser http(s)://host[:porta][/caminho], sem barra final");
        }
    }
}
