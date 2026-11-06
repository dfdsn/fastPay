# H25 — Evidência da transferência de unidades sem duplicar produção

Registro sanitizado. Claude Code, 06/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V21__transferencia_de_unidades.sql` | `item_transfer` é o fato da transferência (linha, linha de produção, comanda de origem e de destino, unidades, o que ficou na origem, etapa, motivo, quem, quando); só `SELECT`/`INSERT` no runtime, com as duas comandas abertas e autor = identidade do contexto; origem ≠ destino. `production_event` ganha `TRANSFERIDO` (sem mudar a etapa, sempre com motivo). `attendance_call` ganha a resolução `TRANSFERIDO`. Nenhum `UPDATE`/`DELETE` novo além da quantidade da alocação, que já existia (H23) |
| Serviço | `production.Transfers`, `JdbcTransferStore`, `JdbcProductionStore.transfer` | Uma transação: trava as duas comandas em ordem de id (a mesma trava do envio, do cancelamento, da conta e do fechamento) e depois a linha de produção; confere a quantidade vista na origem; problemas do consumidor; muda as alocações; sobe a versão da linha (a entrega com os destinos de antes é recusada, H18); histórico `TRANSFERIDO`; fato e auditoria `item.transferido`; avisos `CONTA` das duas, `PEDIDO` das duas e `PRODUCAO` de todos os destinos; `ProductionLineChanged.TRANSFERIDA`. Qualquer falha desfaz tudo |
| Alocações | `Orders.transferInCurrentUnit`, `JdbcOrderStore.addToAllocation` | A origem diminui (das alocações mais recentes, como no cancelamento); o destino soma na alocação que já tem da linha (mesmo zerada) ou ganha uma nova: uma alocação por linha e comanda, e a soma das alocações da linha não muda. Linha enviada, preço do envio e observação ficam |
| Problemas do consumidor | `production.TransferGuard` (porta), `attendance.IssueTransferGuard`, `JdbcIssueStore.pendingForTransfer` | Problema não tratado do item, na origem ou no destino, bloqueia aquele item; da conta, bloqueia saídas da origem e entradas no destino. A porta fica na produção e o atendimento a implementa, para a produção não depender do atendimento (que já depende dela) |
| Avisos de atendimento | `attendance.ProductionCalls`, `JdbcAttendanceStore.openKinds`/`concludeOutside` | Os avisos em aberto da linha (item pronto, impossibilidade) seguem as unidades: cada comanda com unidades ganha o seu, com a modalidade dela; o da comanda que ficou sem unidades termina como `TRANSFERIDO` |
| Leituras | `Orders.SentLine.inTabQuantity`/`transferredQuantity`, `Orders.receivedOfTab`, `GET /api/v1/orders/received?tabId=` | O pedido mostra o que ficou na comanda e o que foi para outras; a comanda de destino lista os itens recebidos, com preço do envio e a comanda de origem |
| API | `TransferController`: `POST /api/v1/tabs/{tabId}/items/{orderLineId}/transfer` `{toTabId, quantity, expectedQuantity, reason}` (`TRANSFERIR_UNIDADES`) | Códigos e mensagens em português (seção 2) |
| Frontend | `orders/tab-orders-page.ts`, `orders.service.ts` | "Transferir" ao lado de "Cancelar" em cada item com unidades na comanda (só com a permissão), escolhendo a comanda aberta de destino, quantas unidades e o motivo; "Recebidos de outras comandas" com a origem, que também podem ser cancelados ou transferidos daqui. O cancelamento (H23) passa a usar as unidades desta comanda |

Nenhuma dependência nova.

## 2. T-03 da H25 (proposta em 06/11/2026, aguardando Diego)

| Proposta | Valor |
|---|---|
| Quem transfere | `TRANSFERIR_UNIDADES`, que já existia: gerente e administrador; garçom só com delegação; produção não. O botão só aparece para quem tem a permissão; o servidor confere de novo em cada chamada |
| Motivo | 5 a 300 caracteres, obrigatório |
| Destino | Qualquer outra comanda aberta da mesma unidade, escolhida na lista das comandas abertas. Comanda de outra unidade não existe para quem transfere (404 `DESTINO_NAO_ENCONTRADO`) |
| Quantidade | Unidades inteiras, de 1 até as ativas do item na comanda de origem; nunca fração de valor (RN-01) |
| Etapa | Qualquer etapa: aguardando, em preparo, pronto e também entregue (só a correção de consumo; a entrega e os instantes ficam). Item ainda não recebido pela produção espera o encaminhamento (409 `ITEM_AGUARDANDO_PRODUCAO`); item sem unidades na origem não tem o que transferir (409 `ITEM_SEM_UNIDADES`) |
| Comandas encerradas | Origem encerrada 409 `COMANDA_ENCERRADA`; destino encerrado 409 `DESTINO_ENCERRADO`. "Em fechamento" (H28) e cobrança existente (H30) recusarão quando existirem |
| Problemas do consumidor | Do item, na origem ou no destino: 409 `PROBLEMA_NO_ITEM`. Da conta da origem: 409 `PROBLEMA_NA_CONTA`; do destino: 409 `PROBLEMA_NA_CONTA_DESTINO`. Transferir não conclui problema (FR-20, FR-24) |
| Desconto | Avaliado nas duas contas na leitura seguinte, sem passo extra: se passar da autoridade de quem concedeu, fica em revisão e bloqueia a cobrança daquela conta (FR-68), sem impedir a transferência |
| Avisos de atendimento | Seguem as unidades: a comanda de destino ganha aviso de item pronto ou de impossibilidade; a que fica sem unidades tem o dela encerrado como transferido |
| Concorrência | A tela manda as unidades que viu na origem; se mudaram (outra correção), 409 `QUANTIDADE_DESATUALIZADA` e a tela relê. As duas comandas são travadas em ordem de id: transferências cruzadas ao mesmo tempo não se bloqueiam |
| Desfazer | Não há "desfazer transferência": corrige-se transferindo de volta, com motivo; o histórico guarda as duas |

## 3. Testes

| Teste | Critério da H25 |
|---|---|
| `TransferIntegrationTest.divideTresUnidadesEmDuasMaisUmaSemDuplicarProducao` | Garçom sem delegação 403 (CA-04); **3 unidades em 2 + 1 com produção única**: uma `production_line` e uma `order_line`, fila com dois destinos (2 e 1), observação e etapa preservadas, versão sobe; preço do envio preservado mesmo com o catálogo mudado; contas das duas no mesmo commit; pedido da origem com `inTabQuantity`/`transferredQuantity`; destino lista o recebido com a origem; fato e auditoria; **conflito de destinos na entrega**: entrega com a versão de antes 409 `VERSAO_DESATUALIZADA` e a lista de entregas com os destinos novos; avisos de item pronto seguem as unidades (o da origem sem unidades termina `TRANSFERIDO`); **linha entregue** transferida de volta sem mudar etapa nem `delivered_at` e sem aviso novo; uma alocação por linha e comanda; histórico com cada passo |
| `…validacoesForaDaUnidadeComandasEncerradasEDelegacao` | Quantidade 0 e acima das ativas, motivo curto, sem destino, destino igual à origem, sem quantidade vista (400 com `field`); quantidade desatualizada 409; item de outra comanda 404; **fora da unidade**: destino de outra loja e inexistente 404 `DESTINO_NAO_ENCONTRADO`, gerente de outra loja 404 `COMANDA_NAO_ENCONTRADA` (também nos recebidos); item ainda não recebido; garçom com delegação transfere; tudo cancelado `ITEM_SEM_UNIDADES`; destino e origem encerrados, sem mudar as alocações |
| `…problemaDoItemEDaContaBloqueiamEDescontoEAvaliadoNasDuasContas` | Problema do item bloqueia o item; problema da conta do destino bloqueia entradas e o da origem, saídas; desconto em reais nas duas contas: a origem passa da autoridade (24,85% > 20%) e fica em revisão, o destino continua sem revisão (6,67%); nova transferência com a conta em revisão continua possível; o problema continua aberto |
| `…transferenciasCruzadasSimultaneasTravamEmOrdemSemImpasse` | **Lock em ordem**: cinco rodadas de A→B e B→A ao mesmo tempo, todas 200, sem impasse; a mesma transferência duas vezes ao mesmo tempo: uma 200, outra 409; versão da linha conta cada transferência |
| `…falhaNoMeioDesfazAsDuasContas` | **Rollback entre contas**: um gatilho de teste faz o último passo (o fato) falhar depois de mexer nas alocações e na linha; nada fica gravado (alocações, versão, histórico, as duas contas); sem o gatilho a mesma transferência passa |
| `MigrationsIntegrationTest` | 21 migrations em banco vazio e a partir da V1 |
| `tab-orders-page.spec.ts` (2 novos, 1 ajustado) | Sem permissão não mostra "Transferir"; destino escolhido entre as comandas abertas, sem a própria; validação local de destino; corpo com destino, quantidade, unidades vistas e motivo; mostra o que ficou e o que saiu; itens recebidos com a origem, corrigíveis aqui (cancelar manda as unidades desta comanda) e a recusa do servidor aparece |

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `ef8e519` | 454 testes, 0 falhas, cobertura atendida |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | Contêiner `node:24.18.0-trixie-slim` | idem | 38 arquivos, 201 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (CI do PR); E2E Playwright (não configurado); celular real na homologação de Diego. Comandas encerradas foram simuladas encerrando-as no banco, porque fechamento e pagamento são da H28/H30. O ranking que não duplica (CA-10) é da H43: aqui fica garantido que a linha e a produção continuam uma só.
