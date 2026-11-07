package tech.malyah.fastpay.identity.infrastructure;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import tech.malyah.fastpay.identity.infrastructure.GoogleIdTokenVerifier.GoogleIdentity;

/**
 * Login Google do consumidor por OpenID Connect com código de autorização e PKCE (H27, P-13): escopos só
 * {@code openid email}, {@code state} e {@code nonce} de uso único. A troca do código acontece entre o servidor e o
 * Google, com o segredo do cliente; o navegador nunca vê tokens. Nenhum token do Google é guardado nem registrado em
 * log: só o "sub", o e-mail e se ele é verificado saem daqui.
 */
@Component
public class GoogleOidcClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcClient.class);
    static final String SCOPES = "openid email";

    private final ConsumerProperties.Google google;
    private final Clock clock;
    private volatile GoogleIdTokenVerifier verifier;
    private final RestClient http;

    GoogleOidcClient(ConsumerProperties properties, Clock clock) {
        this.google = properties.google();
        this.clock = clock;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(google.timeout());
        this.http = RestClient.builder().requestFactory(requests).build();
    }

    public boolean enabled() {
        return google.enabled();
    }

    /** Endereço do Google para onde o navegador vai entrar. */
    public URI authorizationUri(String state, String nonce, String codeChallenge) {
        return UriComponentsBuilder.fromUri(google.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", google.clientId())
                .queryParam("redirect_uri", google.redirectUri().toString())
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUri();
    }

    /** Troca o código pelo ID token e o confere. Vazio se o Google recusar, falhar ou o token não valer. */
    public Optional<GoogleIdentity> exchange(String code, String codeVerifier, String nonce) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", google.redirectUri().toString());
        form.add("client_id", google.clientId());
        form.add("client_secret", google.clientSecret());
        form.add("code_verifier", codeVerifier);
        java.util.Map<?, ?> tokens;
        try {
            tokens = http.post().uri(google.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(java.util.Map.class);
        } catch (RestClientException failed) {
            // Sem o corpo da resposta: pode ecoar o código.
            log.warn("Troca do código do Google falhou: {}", failed.getClass().getSimpleName());
            return Optional.empty();
        }
        if (tokens == null) {
            return Optional.empty();
        }
        return tokens.get("id_token") instanceof String idToken ? verifier().verify(idToken, nonce) : Optional.empty();
    }

    private GoogleIdTokenVerifier verifier() {
        GoogleIdTokenVerifier current = verifier;
        if (current == null) {
            current = GoogleIdTokenVerifier.forProvider(google, clock);
            verifier = current;
        }
        return current;
    }
}
