package tech.malyah.fastpay.mail;

import java.util.Objects;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.mail.infrastructure.MailProperties;

/**
 * Links enviados por e-mail, montados com o endereço público configurado ({@code PUBLIC_BASE_URL}). O token
 * vai no fragmento ({@code #token=}): o navegador não o manda ao servidor nem a proxies e ele não aparece em
 * logs de acesso.
 */
@Component
public class PublicLinks {

    private final String base;

    PublicLinks(MailProperties properties) {
        this.base = properties.publicBaseUrl();
    }

    /** Tela de criar ou redefinir senha (H08); serve também o primeiro acesso do admin (H09). */
    public String passwordResetBase() {
        return base + "/redefinir-senha";
    }

    public String passwordReset(String token) {
        return passwordResetBase() + "#token=" + requireToken(token);
    }

    /** Tela de aceite de convite (H10/H11). */
    public String invitation(String token) {
        return base + "/convite#token=" + requireToken(token);
    }

    private static String requireToken(String token) {
        Objects.requireNonNull(token, "token");
        if (!token.matches("[A-Za-z0-9_-]{16,100}")) {
            throw new IllegalArgumentException("Token de link inválido");
        }
        return token;
    }
}
