package tech.malyah.fastpay.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tech.malyah.fastpay.identity.infrastructure.GoogleIdTokenVerifier.GoogleIdentity;
import tech.malyah.fastpay.testsupport.AdjustableClock;

/**
 * Conferência do ID token do Google (H27): só vale com a nossa chave de teste, o emissor e o público certos, dentro
 * da validade e com o nonce do pedido; fora isso, nenhuma identidade sai.
 */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT = "cliente-teste.apps.googleusercontent.com";
    private static final String NONCE = "nonce-do-pedido";

    private final KeyPair chave = novaChave();
    private final KeyPair outraChave = novaChave();
    private final Instant agora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final AdjustableClock relogio = new AdjustableClock(agora);
    private final GoogleIdTokenVerifier verificador = new GoogleIdTokenVerifier(
            NimbusJwtDecoder.withPublicKey((RSAPublicKey) chave.getPublic()).build(), CLIENT,
            "https://accounts.google.com", relogio);

    @Test
    void tokenCertoDevolveSoSubEmailEVerificacao() {
        assertThat(verificador.verify(token(chave, c -> c.claim("name", "Nome Que Não Guardamos")), NONCE))
                .contains(new GoogleIdentity("1234567890", "Ana@Exemplo.com", true));
    }

    @Test
    void emissorSemHttpsTambemVale() {
        assertThat(verificador.verify(token(chave, c -> c.issuer("accounts.google.com")), NONCE)).isPresent();
    }

    @Test
    void emailNaoVerificadoVemMarcado() {
        assertThat(verificador.verify(token(chave, c -> c.claim("email_verified", false)), NONCE))
                .hasValueSatisfying(identity -> assertThat(identity.emailVerified()).isFalse());
    }

    @Test
    void recusaNonceDiferenteOuAusente() {
        assertThat(verificador.verify(token(chave, c -> {}), "outro-nonce")).isEmpty();
        assertThat(verificador.verify(token(chave, c -> c.claim("nonce", null)), NONCE)).isEmpty();
    }

    @Test
    void recusaOutroPublicoOuOutraParteAutorizada() {
        assertThat(verificador.verify(token(chave, c -> c.audience("outro-cliente")), NONCE)).isEmpty();
        assertThat(verificador.verify(token(chave, c -> c.audience(List.of("outro-cliente", CLIENT))
                .claim("azp", "outro-cliente")), NONCE)).isEmpty();
    }

    @Test
    void recusaOutroEmissor() {
        assertThat(verificador.verify(token(chave, c -> c.issuer("https://falso.example")), NONCE)).isEmpty();
    }

    @Test
    void recusaAssinaturaDeOutraChaveOuTokenMalformado() {
        assertThat(verificador.verify(token(outraChave, c -> {}), NONCE)).isEmpty();
        assertThat(verificador.verify("nao.e.um-token", NONCE)).isEmpty();
        assertThat(verificador.verify("", NONCE)).isEmpty();
        assertThat(verificador.verify(null, NONCE)).isEmpty();
    }

    @Test
    void recusaTokenVencidoDepoisDaFolga() {
        String token = token(chave, c -> c.expirationTime(Date.from(agora.plus(Duration.ofMinutes(5)))));
        relogio.advance(Duration.ofMinutes(5).plusSeconds(59));
        assertThat(verificador.verify(token, NONCE)).isPresent();
        relogio.advance(Duration.ofSeconds(2));
        assertThat(verificador.verify(token, NONCE)).isEmpty();
    }

    @Test
    void recusaSemSubOuSemEmail() {
        assertThat(verificador.verify(token(chave, c -> c.subject(null)), NONCE)).isEmpty();
        assertThat(verificador.verify(token(chave, c -> c.claim("email", null)), NONCE)).isEmpty();
    }

    private String token(KeyPair assinatura, Consumer<JWTClaimsSet.Builder> ajuste) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject("1234567890")
                .audience(CLIENT)
                .claim("azp", CLIENT)
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plus(Duration.ofHours(1))))
                .claim("nonce", NONCE)
                .claim("email", "Ana@Exemplo.com")
                .claim("email_verified", true);
        ajuste.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) assinatura.getPrivate()));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair novaChave() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
