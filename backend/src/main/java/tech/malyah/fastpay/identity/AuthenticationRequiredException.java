package tech.malyah.fastpay.identity;

/** Requisição sem identidade autenticada na sessão. */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException() {
        super("Sessão sem identidade autenticada.");
    }
}
