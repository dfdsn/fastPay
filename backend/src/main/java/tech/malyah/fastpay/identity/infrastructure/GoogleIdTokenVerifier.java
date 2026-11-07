package tech.malyah.fastpay.identity.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Confere o ID token do Google (H27, OpenID Connect Core §3.1.3.7): assinatura RS256 pelas chaves públicas do
 * provedor, emissor, público igual ao nosso client id (e {@code azp}, se vier), validade com até 60 s de folga e o
 * nonce do pedido. Devolve só o "sub", o e-mail e se o e-mail foi verificado (P-13: nada de nome ou foto).
 */
public final class GoogleIdTokenVerifier {

    /** Identidade Google confirmada. */
    public record GoogleIdentity(String subject, String email, boolean emailVerified) {
    }

    private final JwtDecoder decoder;
    private final String clientId;
    private final Set<String> issuers;

    GoogleIdTokenVerifier(JwtDecoder decoder, String clientId, String issuer, Clock clock) {
        this.clientId = clientId;
        this.issuers = alternatives(issuer);
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(timestamps);
        }
        this.decoder = decoder;
    }

    /** Para o Google de verdade: chaves públicas buscadas (e guardadas em cache) do endereço configurado. */
    static GoogleIdTokenVerifier forProvider(ConsumerProperties.Google google, Clock clock) {
        return new GoogleIdTokenVerifier(NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri().toString()).build(),
                google.clientId(), google.issuer(), clock);
    }

    /** A identidade do token, ou vazio se ele não vale por qualquer motivo. */
    public Optional<GoogleIdentity> verify(String idToken, String expectedNonce) {
        if (idToken == null || idToken.isBlank()) {
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException invalid) {
            return Optional.empty();
        }
        String issuer = jwt.getClaimAsString("iss");
        List<String> audience = jwt.getAudience();
        String authorizedParty = jwt.getClaimAsString("azp");
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (issuer == null || !issuers.contains(issuer)
                || audience == null || !audience.contains(clientId)
                || (authorizedParty != null && !authorizedParty.equals(clientId))
                || !expectedNonce.equals(jwt.getClaimAsString("nonce"))
                || jwt.getExpiresAt() == null
                || subject == null || subject.isBlank() || subject.length() > 255
                || email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GoogleIdentity(subject, email, Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))));
    }

    /** O Google emite com e sem {@code https://}; os dois valem. */
    private static Set<String> alternatives(String issuer) {
        String bare = issuer.startsWith("https://") ? issuer.substring("https://".length()) : issuer;
        return Set.of(bare, "https://" + bare);
    }
}
