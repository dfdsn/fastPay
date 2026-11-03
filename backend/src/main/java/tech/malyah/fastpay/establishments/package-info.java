/**
 * Cadastro, vínculos, configuração e ativação de estabelecimentos. Desde H10 o admin fastPay cadastra a
 * unidade (nome, e-mail do dono, CNPJ opcional) em configuração e convida o dono pelos convites do módulo de
 * identidade; ativação e ciclo comercial chegam em H39. Desde H14, a configuração versionada da unidade (serviço,
 * couvert, formatos de desconto, atendimento, saída) e o calendário operacional (fuso e corte com vigência futura)
 * ficam em {@link tech.malyah.fastpay.establishments.EstablishmentSettings}, que as comandas usam para capturar a
 * versão na abertura.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Estabelecimentos")
package tech.malyah.fastpay.establishments;
