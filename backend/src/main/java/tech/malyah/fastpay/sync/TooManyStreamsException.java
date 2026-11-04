package tech.malyah.fastpay.sync;

/** Conexões abertas demais neste contexto (ou no servidor): o cliente segue por polling. */
public class TooManyStreamsException extends RuntimeException {

    public TooManyStreamsException() {
        super("Conexões em tempo real demais abertas.");
    }
}
