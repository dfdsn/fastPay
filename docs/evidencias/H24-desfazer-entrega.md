# H24 — Evidência do desfazer entrega com histórico

Registro sanitizado. Claude Code, 06/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V20__desfazer_entrega.sql` | `production_event` ganha as ações `ENTREGA_DESFEITA` (entregue → pronto, com motivo) e `OCORRENCIA_ENTREGA` (entregue → entregue, com motivo) e a coluna `invalidates_event_id`, que aponta para o `ENTREGUE` cuja amostra de tempo deixa de valer (só no desfazer, sempre no desfazer; cada entrega invalidada uma vez, índice único). Índice das entregues recentes. Nenhum `UPDATE`/`DELETE` novo no histórico |
| Regras | `production.domain.ProductionRules.undoDelivery` | Só linha entregue, com unidades ativas, e com todas as comandas dessas unidades abertas; senão diz por quê (`NAO_ENTREGUE`, `SEM_UNIDADES_ATIVAS`, `COMANDA_ENCERRADA`) |
| Serviço | `production.DeliveryCorrections`, `JdbcProductionStore` (`delivered`, `undoDelivery`, `lastDelivery`, `audit`) | Uma transação: trava as comandas com unidades ativas da linha em ordem de id (a mesma trava do envio, do cancelamento, da conta e do fechamento) e depois a linha; confere a versão; volta a pronto (instante do pronto preservado, o da entrega limpo); histórico `ENTREGA_DESFEITA` com a entrega invalidada; auditoria `entrega.desfeita`; aviso `PRODUCAO`; `ProductionLineChanged.ENTREGA_DESFEITA` abre aviso novo de item pronto no atendimento (H20). Com comanda encerrada, só a ocorrência (`OCORRENCIA_ENTREGA`, auditoria `entrega.ocorrencia`), sem mudar linha, conta ou pagamento |
| API | `DeliveryCorrectionController`: `GET /api/v1/production/delivered`, `POST /api/v1/production/lines/{id}/undo-delivery` e `/delivery-occurrence` `{version, reason}` (`DESFAZER_ENTREGA`) | Códigos e mensagens em português; comanda encerrada orienta a ocorrência e a devolução pelo gerente |
| Leituras | `Tabs.Label.closed`, `Production.Destination.closed`, `Production.delivered` | Cada destino diz se a comanda já foi encerrada |
| Frontend | `production/deliveries-page.ts` ("Entregues recentemente", só com a permissão: "Desfazer entrega" ou "Ocorrência", com motivo), `production.service.ts` | A linha desfeita volta para "Prontos para entregar"; destino encerrado aparece como "encerrada" |

Nenhuma dependência nova.

## 2. T-03 da H24 (proposta em 06/11/2026, aprovada por Diego em 06/11/2026 (cartão de decisão no thread da H24, "Aprovar como proposto"))

| Proposta | Valor |
|---|---|
| Quem desfaz | `DESFAZER_ENTREGA`, que já existia: gerente e administrador; garçom só com delegação; produção não. A seção só aparece para quem tem a permissão; o servidor confere de novo em cada chamada |
| Motivo | 5 a 300 caracteres, obrigatório no desfazer e na ocorrência |
| Onde | Tela de entregas, "Entregues recentemente": as 50 entregas mais recentes, das mais novas às mais antigas |
| Prazo | Sem prazo, enquanto todas as comandas com unidades ativas da linha estiverem abertas |
| Depois de desfazer | A linha volta a "pronto" (o instante do pronto fica, o da entrega é limpo), aparece de novo nas entregas e abre aviso novo de item pronto para quem atende; a entrega anterior fica no histórico, inválida para a métrica; a nova entrega válida redefine o tempo |
| Comanda encerrada (paga) | Não desfaz. Registra ocorrência no histórico da linha, com motivo; não reabre, não mexe na conta e não devolve (a devolução, se couber, é do gerente pela história de devoluções). Ocorrência só nesse caso: com tudo aberto, o caminho é desfazer |
| Tudo cancelado depois da entrega | Não há o que desfazer (409 `ITEM_CANCELADO`) |
| Concorrência | A tela manda a versão lida; se mudou (outra entrega, transferência, cancelamento), a ação é recusada e a tela relê. As comandas são travadas antes da linha: o fechamento ao mesmo tempo (H28) espera ou faz esperar |

## 3. Testes

| Teste | Critério da H24 |
|---|---|
| `DeliveryCorrectionIntegrationTest.desfazerVoltaAProntoComHistoricoENovaEntregaSubstituiAAmostra` | Não entregue recusa; garçom sem delegação 403 na lista e na ação (CA-04); outra unidade 404; motivo curto e sem versão 400; versão antiga 409; lista com o destino aberto; desfazer volta a `PRONTO` com `readyAt` preservado, `deliveredAt` nulo e versão nova; reaparece nas entregas com aviso novo de item pronto (o antigo continua `ENTREGUE`); repetir recusa; **nova entrega substitui a amostra antiga**: `delivered_at` novo, histórico `INICIADO`/`PRONTO`/`ENTREGUE`/`ENTREGA_DESFEITA`/`ENTREGUE`, desfazer aponta para a entrega errada com motivo e autor, só a nova conta como amostra válida; auditoria; garçom com delegação desfaz e, **com a permissão revogada**, recebe 403 |
| `…destinosMultiplosComUmEncerradoSoRegistramOcorrencia` | **Destinos múltiplos, um fechado**: com as duas abertas a ocorrência é recusada; com uma encerrada a lista marca o destino, desfazer recusa com `COMANDA_ENCERRADA` sem mudar a linha, e a ocorrência fica no histórico com motivo e instante, sem mudar versão nem etapa; auditoria; motivo curto 400; garçom sem delegação 403 |
| `…corridaComFechamentoEsperaATravaDaComanda` | **Corrida com fechamento**: uma conexão trava a comanda como o fechamento fará (H28); o desfazer fica esperando a trava; o fechamento encerra e confirma; o desfazer termina com `COMANDA_ENCERRADA` e a linha continua entregue |
| `…entregueComTudoCanceladoNaoTemOQueDesfazer` | Entregue e depois cancelada por inteiro (H23): `ITEM_CANCELADO` |
| `ProductionRulesTest.desfazerEntregaSoEntregueComUnidadesAtivasEComandasAbertas` | Regras puras: etapas, sem unidades ativas, comanda encerrada; as ações novas não são etapas da produção |
| `MigrationsIntegrationTest` | 20 migrations em banco vazio e a partir da V1 |
| `deliveries-page.spec.ts` (3 novos) | Sem permissão não pede nem mostra entregues; desfazer com motivo (validação local, corpo com a versão lida), a linha volta para os prontos; comanda encerrada só oferece ocorrência, com aviso de que nada é reaberto nem devolvido; recusa 409 mostra a mensagem e relê |

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `9ff79c1` | 449 testes, 0 falhas |
| `npm run lint`, `npm run test:ci`, `npm run build` | Contêiner `node:24.18.0-trixie-slim` | idem | 38 arquivos, 199 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (CI do PR); E2E Playwright (não configurado); celular real na homologação de Diego. Destinos múltiplos foram montados dividindo a alocação direto no banco, porque a transferência é da H25; a comanda paga foi simulada encerrando-a no banco, porque fechamento e pagamento são da H28/H30.
