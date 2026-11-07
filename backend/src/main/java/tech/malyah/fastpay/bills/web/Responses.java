package tech.malyah.fastpay.bills.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import tech.malyah.fastpay.bills.Bills;
import tech.malyah.fastpay.bills.Bills.Done;
import tech.malyah.fastpay.bills.Bills.Invalid;
import tech.malyah.fastpay.bills.Bills.Outcome;
import tech.malyah.fastpay.bills.Bills.Refused;

/** Respostas da conta (H22): recusas viram problemas HTTP com código e texto em português. */
final class Responses {

    private Responses() {
    }

    static <T> T respond(Outcome<T> outcome) {
        return switch (outcome) {
            case Done<T> done -> done.value();
            case Invalid<T> invalid -> {
                ErrorResponseException e = error(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", invalid.message());
                e.getBody().setProperty("field", invalid.field());
                throw e;
            }
            case Refused<T> refused -> throw refusal(refused.refusal());
        };
    }

    private static ErrorResponseException refusal(Bills.Refusal refusal) {
        return switch (refusal) {
            case COMANDA_NAO_ENCONTRADA -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Comanda não encontrada nesta unidade. Atualize a lista.");
            case COMANDA_ENCERRADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda já foi encerrada: a conta não muda mais.");
            case COMANDA_EM_FECHAMENTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda está em fechamento: só a taxa de serviço pode mudar. Para corrigir a conta, reabra "
                            + "a comanda com o motivo.");
            case COMANDA_NAO_EM_FECHAMENTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda não está em fechamento. Atualize a tela.");
            case ITENS_PENDENTES -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Ainda há itens aguardando, em preparo, prontos ou sem atendimento. Entregue ou cancele antes de "
                            + "fechar a conta.");
            case PROBLEMA_EM_ABERTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Há um problema informado ainda não resolvido. Resolva o problema antes de fechar a conta.");
            case DESCONTO_EM_REVISAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O desconto precisa ser revisado por alguém com limite antes de fechar a conta.");
            case CONTA_MUDOU -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A conta mudou desde que a tela foi carregada. Confira o novo total e tente de novo.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O desconto mudou desde que a tela foi carregada. Confira a conta e tente de novo.");
            case FORMATO_NAO_ACEITO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A configuração da unidade não aceita este formato de desconto. Use o outro formato.");
            case SEM_CONSUMO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "A comanda ainda não tem consumo: não há sobre o que dar desconto.");
            case DESCONTO_ACIMA_DO_CONSUMO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O desconto não pode passar do consumo da comanda.");
            case DESCONTO_ACIMA_DO_LIMITE -> error(HttpStatus.FORBIDDEN, refusal.name(),
                    "Este desconto passa do seu limite. Peça a alguém com limite maior.");
            case SEM_DESCONTO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda não tem desconto. Atualize a conta.");
            case DESCONTO_SEM_REVISAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Este desconto está dentro do limite de quem concedeu: não precisa de revisão.");
            case SERVICO_DESABILITADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda não tem taxa de serviço.");
            case SEM_COUVERT -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda não tem couvert cobrado. Atualize a conta.");
            case COUVERT_NAO_MANUAL -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda foi aberta sem couvert lançado pela equipe: não há couvert para incluir.");
            case COUVERT_DESABILITADO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O couvert está desligado na configuração da unidade agora.");
            case COUVERT_JA_INCLUIDO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Esta comanda já tem couvert. Atualize a conta.");
            case COUVERT_JA_COBRADO_NA_VISITA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O couvert desta visita já está em outra comanda do cliente: ele não se repete.");
            case COUVERT_DISPENSADO_NA_VISITA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O couvert foi dispensado nesta visita (indevido ou isenção): não volta a ser cobrado.");
            case COUVERT_AGUARDA_CORRECAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "O couvert desta visita saiu por correção: use Incluir correção com o valor correto.");
            case COUVERT_SEM_DUPLICIDADE -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Não há outro couvert cobrado nesta visita: não é duplicidade. Escolha outro motivo.");
            case COUVERT_SEM_CORRECAO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Não há couvert cancelado por correção nesta visita.");
        };
    }

    static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
