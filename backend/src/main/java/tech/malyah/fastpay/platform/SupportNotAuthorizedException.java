package tech.malyah.fastpay.platform;

/**
 * Consulta de suporte sem concessão vigente do dono para esta identidade e unidade (FR-66, CA-28): nunca houve,
 * venceu, foi encerrada, a unidade não existe ou a identidade deixou de ser admin fastPay. Mesma resposta em
 * todos os casos.
 */
public class SupportNotAuthorizedException extends RuntimeException {

    public SupportNotAuthorizedException() {
        super("Sem autorização de suporte vigente para a unidade.");
    }
}
