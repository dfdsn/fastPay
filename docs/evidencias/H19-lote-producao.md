# H19 — Evidência das operações em lote de produção

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Regras | `production.domain.ProductionRules.BATCH_MAX` | 50 linhas por lote (D-13, FR-69), parâmetro técnico global |
| Serviço | `production.Production.batch` | Valida o pedido inteiro uma vez (vínculo, `OPERAR_PRODUCAO`, ação, 1–50 linhas distintas com versão); depois cada linha roda na **própria transação**, com vínculo e permissão conferidos de novo e as mesmas regras das ações individuais (linha travada, versão esperada, etapa, impossibilidade). Uma recusa não desfaz as que passaram. Mesmo `correlation_id` no histórico para as linhas do lote |
| Repetição | `JdbcProductionStore.applied` | Versão mudou porque a mesma ação já foi feita sobre a versão enviada (`production_event` com a ação e `line_version = version + 1`): `IGNORADA` (`JA_APLICADA`), sem avançar uma segunda etapa |
| API | `production.web.ProductionController` | `POST /api/v1/production/batch` `{action: INICIAR|PRONTO, lines: [{id, version}]}` → 200 `{concluded, ignored, failed, lines: [{lineId, status, code, message}]}` na ordem do pedido; 400 `DADOS_INVALIDOS` (`field` `action`/`lines`) e 403 recusam o lote inteiro sem tocar em linha |
| Frontend | `production/production-page.ts`, `production.service.ts` | Marcar itens ou todos; "Iniciar marcados (n)" e "Marcar prontos os marcados (n)" enviam só os marcados na etapa certa e sem impossibilidade; acima de 50 avisa sem enviar; resumo "Lote: 2 iniciados, 1 já estava feito, 1 não avançou." e lista de quem não avançou com o motivo; os que falharam continuam marcados |

Nenhuma migration nova; nenhuma dependência nova.

## 2. Proposta aguardando Diego (T-03 da H19)

| Proposta | Valor |
|---|---|
| Ações em lote | Só "iniciar" e "marcar pronto"; entregar e "não é possível atender" seguem uma linha por vez |
| Formato | Uma ação por lote, até 50 linhas |
| Pedido inválido | Linha repetida, sem versão, vazio ou mais de 50: recusa o lote inteiro antes de mexer em qualquer linha |
| Transação | Uma por linha, com vínculo e permissão reconferidos |
| Repetição | Mesmo lote de novo, ou a mesma ação já feita por outra pessoa sobre a versão lida: "já estava feito" (`IGNORADA`), não falha |
| Tela | Envia só os marcados na etapa certa; mantém marcados os que falharam |

## 3. Testes

| Teste | Critério da H19 |
|---|---|
| `ProductionIntegrationTest.loteMistoTemResultadoPorLinhaENaoDesfazAsQuePassaram` | **Lote misto** de 7: uma permitida (concluída), uma conflitante (`VERSAO_DESATUALIZADA`), uma impossível, uma já iniciada por outra pessoa (ignorada), uma sem preparo (`ETAPA_INVALIDA`), uma **de outra unidade** e uma inexistente (`LINHA_NAO_ENCONTRADA`, autorização item a item pela RLS); as recusas não mexem em nada; **repetição do mesmo lote** não avança a segunda etapa (ignoradas); lote de pronto; histórico com uma etapa por avanço e o mesmo `correlation_id` no lote; nenhum evento na outra unidade |
| `…loteRecusadoInteiroSoPorPedidoInvalidoOuSemPermissao` | **51 linhas** recusadas sem tocar em nenhuma; 50 aceitas com sucesso parcial; linha repetida, vazio, sem versão, sem ação e ação desconhecida (`ENTREGAR`) 400; garçom 403 |
| Demais de `ProductionIntegrationTest` (5) | Ações individuais da H18 sem mudança |
| `production-page.spec.ts` (2 novos) | Marcar todos, só os elegíveis vão no lote com a versão lida, resumo e falha com motivo, quem falhou continua marcado; acima de 50 não envia; lote repetido mostra "já estavam feitos" |

Linha cancelada: o cancelamento ainda não existe (H23). O lote já trata qualquer recusa por linha sem afetar as demais; a H23 acrescenta o caso ao teste do lote.

## 4. Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw -B -ntp clean verify` (backend) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Testcontainers PostgreSQL 18.6 | branch `feature/H19-lote-producao` (antes do commit) | 375 testes, 0 falhas; BUILD SUCCESS (JaCoCo, Modulith, enforcer) |
| `./scripts/verify.sh frontend` | Contêiner `node:24.18.0-trixie-slim`, npm 11.16.0 | idem | `npm ci`, lint sem erros, 171 testes (32 arquivos), linhas 94,88% / branches 87,3%, build ok |

Não executados: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); teste manual num tablet de cozinha e na homologação de Diego.
