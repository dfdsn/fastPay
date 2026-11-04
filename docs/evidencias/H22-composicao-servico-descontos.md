# H22 — Evidência da composição da conta, serviço e descontos

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V18__conta_desconto_servico.sql` | `tab_discount` (um ativo por comanda; fixo em reais ou percentual com 4 casas; motivo; quem concedeu e o limite dela na hora; consumo e desconto daquele momento; revisão com quem revisou e o limite; encerramento por substituição ou retirada com motivo, nunca apagado); `tab_service_choice` (cada retirada/devolução do serviço, pelo cliente via acesso ou pela equipe; vale a última). RLS: só a equipe concede/revisa/retira, em nome próprio e na comanda ativa; o cliente sem identidade só grava a escolha pelo próprio acesso; sem `DELETE`. Tópico `CONTA` em `sync_change` |
| Regras | `bills.domain.BillRules` | C/D/K/B/S/total com BigDecimal e HALF_UP 2 casas; D arredondado antes de formar a base; fixo efetivo = min(valor, C); limite comparado sem divisão (D × 100 > limite × C); percentual compara o percentual concedido (o centavo do arredondamento não vira excesso); total nunca negativo; formatos e motivo |
| Serviço | `bills.Bills`, `JdbcBillStore` | Conta calculada a cada leitura, nunca vinda da tela; comanda travada (`Tabs.forBillInCurrentUnit`, mesma trava do envio) para conceder, revisar, retirar e escolher o serviço; desconto atual esperado (`currentDiscountId`/`discountId`); formatos da configuração vigente; limite da pessoa lido na transação (P-17); auditoria (`desconto.concedido`, `desconto.revisado`, `desconto.retirado`, `servico.retirado`, `servico.devolvido`); aviso `CONTA`; contratos `snapshotInCurrentUnit` e `blocksChargeInCurrentUnit` para H28/H30 |
| API | `BillController` (`/api/v1/tabs/{id}/bill`, `/discount`, `/discount/remove`, `/discount/review`, `/service`), `GuestBillController` (`/api/v1/guest/tabs/{id}/bill`, `/service`), `Responses` | Valores como texto decimal; códigos e mensagens em português; cliente sem acesso recebe o mesmo 404 da H15 |
| Apoio | `Orders.chargedLinesOfTabInCurrentUnit`, `Tabs.forBillInCurrentUnit`, `sync.Topic.CONTA` (também para o consumidor) | Lançamentos de consumo com o preço do envio; versão capturada e trava da comanda |
| Frontend | `bills/bill-panel.ts` (na tela da comanda da equipe), `guest/guest-bill.ts` (na comanda do cliente), `bills/bills.service.ts` | Consumo, desconto, couvert, serviço e total separados; conceder/trocar, revisar e retirar desconto; aviso de revisão e bloqueio; retirar/devolver serviço; avisos `PEDIDO`/`CONTA` relêem a conta |

Nenhuma dependência nova.

## 2. T-03 da H22 (proposta em 04/11/2026, aguardando Diego)

| Proposta | Valor |
|---|---|
| Desconto em reais | Maior que zero, até 2 casas (mais casas recusadas, não arredondadas); na concessão não pode passar do consumo atual (recusa, não corta); depois de uma redução vale até o consumo |
| Desconto percentual | Maior que 0 e até 100%, até 4 casas; 100% permitido |
| Motivo | 5 a 300 caracteres, para conceder e para retirar |
| Formatos | Conferidos na configuração **vigente** na hora de conceder (como a H14 registrou), não na capturada na abertura |
| Sem consumo | Não concede desconto (nada a descontar) |
| Substituir | Conceder de novo substitui o ativo; a tela manda o desconto que estava vendo e, se mudou, recebe 409 para conferir |
| Retirar | Quem tem `CONCEDER_DESCONTO`, com motivo, qualquer que seja o limite (retirar nunca passa do limite) |
| Revisão (FR-68) | Só desconto em reais entra em revisão (o percentual acompanha o consumo). Revisa quem tem `CONCEDER_DESCONTO` e limite que cubra o percentual efetivo de agora; o limite dele passa a ser a autoridade do desconto. Trocar ou retirar também resolve. Cobrança bloqueada enquanto pendente; cancelamento e transferência não |
| Serviço | O cliente, pelo próprio acesso, ou a equipe (`OPERAR_COMANDA`) a pedido dele, retira ou devolve sem motivo nem autorização, enquanto a comanda está aberta; repetir não grava outra escolha |
| Couvert nesta história | K = couvert automático da configuração capturada na abertura; couvert manual, dispensa e contestação são da H26 |
| O que o cliente vê | Itens com preço e total da linha; consumo, valor do desconto (sem motivo nem quem concedeu), couvert, serviço e total |
| Quem vê a conta | `OPERAR_COMANDA` (garçom, gerente, administrador); produção não |

## 3. Testes

| Teste | Critério da H22 |
|---|---|
| `BillRulesTest.tabelaDeBases` (12 linhas) e `exemploDaEspecificacao` | **Tabela de bases do serviço**: antes/depois do desconto, com e sem couvert na base; **centavos** HALF_UP (12,5% de 0,20; meio centavo do serviço; 33,3333%); **desconto 100%** com e sem couvert, nas duas bases; fixo maior que o consumo vira o consumo; total = C − D + K + S e 0 ≤ D ≤ C em todas |
| `…servicoRetiradoOuDesabilitadoZeraS`, `…contaVaziaEValoresGrandesSemOverflow` | **Retirada**; conta vazia; **overflow**: valores muito acima do máximo de uma comanda sem perder centavo |
| `…limiteComparaSemDividir`, `…revisaoSoQuandoAReducaoPassaDaAutoridade`, `formatosAceitos`, `formatosRecusados`, `motivo` | **Limite** exato (33,3333% vs 33,3334%); revisão só quando a redução passa da autoridade; centavo do percentual não vira excesso; valores fora do formato (casas a mais, expoente, 10.000.000, 100,0001%) recusados |
| `BillsIntegrationTest.composicaoComServicoPadraoERetiradaPeloCliente` | Conta sem pedidos e com pedidos (preço do envio); cliente vê os valores e **retira o serviço** sem autorização; equipe devolve; escolha repetida não grava; auditoria; cliente de outra comanda e sem acesso 404; produção 403; outra unidade 404; comanda encerrada recusa |
| `…autoridadeFormatoEMotivoDoDescontoNoServidor` | **Autorização no backend**: garçom sem permissão 403 (mesmo com dados inválidos); delegada com limite 0% recusa; limite 10% aceita 9,16 de 91,60 e recusa 9,17 e 10,0001%; valor/percentual/tipo/motivo inválidos 400; acima do consumo 409; sem consumo 409; um por comanda (desconto desconhecido 409) e substituição; **desconto 100%** zera também o serviço após desconto; cliente vê o valor sem motivo; retirar com motivo e com o desconto atual; formato fora da configuração vigente 409 |
| `…reducaoDoConsumoMarcaRevisaoEBloqueiaCobranca` | **Redução pós-concessão** (consumo reduzido direto no banco até existir a H23): 20,30 de 101,50 (20%) vira 24,85% de 81,70 → revisão e cobrança bloqueada; com o consumo abaixo do valor, D = C e total 0,00; revisão recusada para quem não cobre 100%, aceita pela administradora; snapshot para H28/H30 |
| `…couvertAutomaticoCapturadoNaAberturaEServicoDesabilitado` | Comanda aberta antes da mudança mantém a configuração (sem couvert, 10%); depois: couvert 15,00 na base antes do desconto, 12,5% → 6,36; desconto 100% não toca o couvert; serviço desabilitado não pode ser retirado |
| `…duasConcessoesSimultaneasGravamUmDesconto` | Duas concessões ao mesmo tempo: uma grava, a outra recebe `VERSAO_DESATUALIZADA`; um desconto ativo |
| `MigrationsIntegrationTest` | 18 migrations do zero e da V1 em diante |
| Frontend `bills/bill-panel.spec.ts`, `guest/guest-bill.spec.ts`; specs da comanda da equipe e do cliente | Valores como o servidor mandou, revisão e bloqueio visíveis, corpo das chamadas com o desconto atual, recusas em português, serviço retirado/devolvido, sem formulário sem permissão |

## 4. Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw -B clean verify` (backend: JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18.6 (Testcontainers) | branch `feature/H22-composicao-servico-descontos` | Verde: 439 testes, 0 falhas |
| `npm ci`, `npm run lint`, `npm run test:ci`, `npm run build` | Contêiner `node:24.18.0-trixie-slim` | mesma branch | Verde: lint sem erros, 192 testes em 38 arquivos, build gerado (aviso antigo em `team-page.ts`) |

Não executados: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); celular real do cliente (homologação de Diego). A redução de consumo foi feita direto no banco porque o cancelamento é da H23; a cobrança real que respeita o bloqueio é da H30.
