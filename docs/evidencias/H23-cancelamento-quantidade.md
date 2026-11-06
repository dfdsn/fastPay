# H23 — Evidência do cancelamento de quantidade e da ciência da produção

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V19__cancelamento_de_itens.sql` | `item_cancellation` (fato do cancelamento: linha, comanda, unidades, restante na comanda, etapa da linha na hora, motivo, quem, quando, se exige ciência, quem deu ciência e quando; nunca apagado nem editado, só a ciência é gravada depois). `tab_allocation.quantity` passa a aceitar 0 e ganha `UPDATE` só da quantidade, em comanda ativa e com identidade. `production_line` ganha `cancelled_quantity`, `cancelled_at` e a etapa `CANCELADA` (todas as unidades canceladas antes da entrega; entregue continua `ENTREGUE`). `production_event` ganha a ação `CANCELADO`. `attendance_call` ganha a resolução `CANCELADO` |
| Regras | `production.domain.ProductionRules.cancel` | Etapa depois do cancelamento, ciência só em preparo ou pronto com setor, cancelamento total encerra a linha e resolve a impossibilidade; linha cancelada não avança, não é sinalizada e não atrasa |
| Serviço | `production.Cancellations`, `JdbcCancellationStore` | Uma transação: trava a comanda (a mesma trava do envio, da conta e do fechamento) e depois a linha de produção; confere as unidades ativas que a tela viu; diminui as alocações (`Orders.cancelInCurrentUnit`, a conta da H22 muda no mesmo commit); soma as canceladas na linha e incrementa a versão (a entrega com os destinos de antes é recusada, contrato da H18); histórico da produção; fato do cancelamento; auditoria `item.cancelado` e `item.cancelamento_ciente`; avisos `PEDIDO`, `CONTA` e `PRODUCAO`; linha encerrada publica `ProductionLineChanged.CANCELADA` e o atendimento conclui os avisos da linha |
| API | `CancellationController`: `POST /api/v1/tabs/{tabId}/items/{orderLineId}/cancel` `{quantity, expectedQuantity, reason}` (`CANCELAR_ITEM`); `GET /api/v1/production/cancellations?sectorId=` e `POST /api/v1/production/cancellations/{id}/acknowledge` (`OPERAR_PRODUCAO`) | Códigos e mensagens em português; comanda encerrada orienta a devolução |
| Leituras | `Orders.SentLine` (`activeQuantity`, `cancelledQuantity`), `Production.Line` (`quantity` = restante, `originalQuantity`, `cancelledQuantity`; destino zerado não aparece) | Original, cancelado e restante em todas as telas (FR-20) |
| Frontend | `orders/tab-orders-page.ts` (cancelar com quantidade e motivo, só para quem tem a permissão; enviado/cancelado/restante), `production/production-page.ts` ("Cancelamentos para dar ciência" com som, enviado/cancelado na fila) | A correção não espera a ciência; a produção vê o que parou ou diminuiu |

Nenhuma dependência nova.

## 2. T-03 da H23 (proposta em 04/11/2026, aprovada por Diego em 06/11/2026 ("Pode seguir com o T-03 como sugerido"))

| Proposta | Valor |
|---|---|
| Quem cancela | `CANCELAR_ITEM`, que já existia: gerente e administrador; garçom só com delegação. O botão só aparece para quem tem a permissão; o servidor confere de novo |
| Motivo | 5 a 300 caracteres, obrigatório em qualquer etapa |
| Quantidade | Unidades inteiras, de 1 até as ativas na comanda (no máximo 99, D-13) |
| Ciência da produção | Exigida só quando a linha com setor estava em preparo ou pronta. Aguardando preparo: a fila só diminui. Entregue: só a correção financeira, a entrega fica no histórico. Sem setor: não há produção a avisar |
| Quem dá ciência | Qualquer pessoa com `OPERAR_PRODUCAO` na unidade (a lista pode ser filtrada pelo setor); repetir, ou outra pessoa já ter dado, responde "já tinha ciência" e não muda nada |
| Correções simultâneas | A tela manda as unidades ativas que estava vendo; se mudaram, 409 `QUANTIDADE_DESATUALIZADA` e a tela relê (a segunda correção nunca cancela de novo a mesma unidade) |
| Cancelamento total | Antes da entrega encerra a linha (`CANCELADA`): sai da fila e da entrega, retira a sinalização de impossibilidade e conclui os avisos de atendimento da linha como `CANCELADO`. Depois da entrega a linha continua entregue |
| Item ainda não recebido pela produção | Recusado (409 `ITEM_AGUARDANDO_PRODUCAO`) até a produção receber (segundos); se o encaminhamento falhou, reprocessar antes |
| Comanda encerrada (paga) | Recusa (409 `COMANDA_ENCERRADA`) com orientação para a devolução pelo gerente; nada é apagado. "Em fechamento" (H28) e cobrança existente (H30) passam a recusar quando existirem |
| Problema do consumidor | Cancelar não conclui o problema (FR-20): quem atende conclui pelo fluxo da H21 |
| Lista de ciência | Até 200 por leitura, dos mais antigos aos mais novos; som (se ligado no aparelho, H20) quando chega um cancelamento novo |

## 3. Testes

| Teste | Critério da H23 |
|---|---|
| `CancellationIntegrationTest.cancelamentoAntesDuranteEDepoisDoPreparoComCienciaDaProducao` | **Antes do preparo**: fila diminui, sem ciência, versão sobe, conta cai (155,70 → 119,80), pedido mostra enviado/ativo/cancelado; **em preparo**: ciência exigida, lista com motivo, quem, quando, comanda e setor; filtro por setor; garçom não vê nem dá ciência; **ciência repetida** responde `alreadyAcknowledged` sem mudar o instante; **pronto**: entrega com versão antiga recusada, cancelar o resto encerra a linha (`CANCELADA`), aviso de pronto concluído como `CANCELADO`, `ready_at` preservado e histórico `CANCELADO`/`INICIADO`/`CANCELADO`/`PRONTO`/`CANCELADO`; **depois da entrega**: linha continua `ENTREGUE` com 2 canceladas, sem ciência; nada apagado (alocações zeradas e 4 fatos); garçom sem delegação recebe 403 (CA-04) |
| `…quantidadeInvalidaComandaEncerradaEDelegacao` | **Quantidade inválida** (0, acima das ativas, sem quantidade esperada), motivo curto, quantidade desatualizada, item de outra comanda, outra unidade; item ainda não recebido pela produção; garçom com delegação cancela; **pagamento existente**: comanda encerrada recusa com orientação de devolução e não mexe nas alocações |
| `…duasCorrecoesConcorrentesCancelamUmaVez` | **Duas correções concorrentes** (gerente e administradora ao mesmo tempo): uma 200, outra 409; uma unidade cancelada e a versão sobe uma vez |
| `…cancelamentoResolveImpossibilidadeSemConcluirProblemaDoConsumidor` | Parcial mantém a sinalização; total retira, conclui o aviso de impossibilidade como `CANCELADO`; problema do consumidor continua `ABERTO`; **lote da H19 com linha cancelada** falha com `ETAPA_INVALIDA` sem impedir as demais |
| `BillsIntegrationTest.reducaoDoConsumoMarcaRevisaoEBloqueiaCobranca` (H22, agora com o cancelamento real) | **Desconto em revisão**: o gerente cancela 2 chopps e depois as batatas pela API; o desconto em reais passa da autoridade, a conta entra em revisão e a cobrança é bloqueada, sem impedir a correção |
| `ProductionRulesTest.cancelamentoAjustaFilaPedeCienciaSoEmPreparoOuProntoEEncerraALinha` | Regras puras: etapa, ciência, cancelamento total, entregue, sem setor, impossibilidade, limites |
| `MigrationsIntegrationTest` | 19 migrations em banco vazio e a partir da V1 |
| `tab-orders-page.spec.ts` (2 novos) | Cancelar com motivo (validação local, corpo com a quantidade vista), enviado/cancelado/restante; sem permissão sem botão; 409 mostra a recusa e relê |
| `production-page.spec.ts` (2 novos) | Lista de ciência, linha com enviado e cancelado, ciência e "já tinha ciência", cancelamento total pede parar o preparo |

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `585f2c1` | 444 testes, 0 falhas |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | Contêiner `node:24.18.0-trixie-slim` | idem | 38 arquivos, 196 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (CI do PR); E2E Playwright (não configurado); celular e tela da cozinha reais (homologação de Diego). Pagamento existente foi simulado com a comanda encerrada direto no banco, porque o pagamento é da H30.
