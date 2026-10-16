package tech.malyah.fastpay.identity;

/** Ação exclusiva do admin fastPay pedida por outra identidade (dono, equipe de loja ou consumidor). */
public class PlatformAccessDeniedException extends RuntimeException {

    public PlatformAccessDeniedException() {
        super("Ação exclusiva do admin fastPay.");
    }
}
