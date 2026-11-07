package tech.malyah.fastpay.identity;

/** Requisição do consumidor sem conta Google conectada nesta sessão (H27). */
public class ConsumerSignInRequiredException extends RuntimeException {

    public ConsumerSignInRequiredException() {
        super("Sessão sem consumidor conectado.");
    }
}
