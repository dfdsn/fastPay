package tech.malyah.fastpay.identity;

/** Operação de loja pedida sem unidade escolhida na sessão. */
public class UnitNotSelectedException extends RuntimeException {

    public UnitNotSelectedException() {
        super("Nenhuma unidade escolhida na sessão.");
    }
}
