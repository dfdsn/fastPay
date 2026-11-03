# H16 — Evidência de rascunho, envio atômico e preço capturado

Registro sanitizado. Claude Code, 03/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, chaves de operação aleatórias geradas a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V13__pedido_envio_e_producao.sql` | `customer_order` (chave de operação única por unidade, impressão SHA-256 do conteúdo, autor, 1–50 linhas); `order_line` (posição, nome, preço do envio, quantidade 1–99, observação até 140, setor; sem `UPDATE`/`DELETE` no runtime); `tab_allocation` (a linha inteira na comanda; transferência e cancelamento nas H25/H23); `production_line` (uma por linha enviada, `AGUARDANDO_PREPARO` ou, sem setor, `PRONTO`; etapas na H18). Pedido e alocação só entram em comanda ativa (policy de `INSERT`); FKs compostas com a unidade; RLS `ENABLE + FORCE` |
| Regras puras | `orders.domain.OrderRules` | Normaliza e limita as linhas com o campo exato do erro; preço visto com até duas casas; revisão (esgotado/ausente e preço mudado, um por produto); impressão do conteúdo para a chave não servir a outro pedido |
| Envio | `orders.Orders`, `orders.web.OrderController` (`/api/v1/orders`) | Trava a comanda (`Tabs.lockForOrderInCurrentUnit`, contrato com o fechamento H28/H29), procura a chave, trava os produtos para leitura (`Catalog.forOrderInCurrentUnit`, `FOR SHARE` por id), confere e grava tudo no mesmo commit, inclusive auditoria e o job `production.receber_pedido`. Consulta da operação, pedidos da comanda com a situação do encaminhamento, e reprocessamento do encaminhamento esgotado |
| Produção | `production.ProductionIntake` (executor do job), `production.infrastructure.JdbcProductionStore` | Cria a sequência de produção de cada linha, idempotente (`ON CONFLICT DO NOTHING` na linha) |
| Fila de jobs | `jobs.Jobs.statesInCurrentTransaction`, `retryExhaustedInCurrentTransaction` | Situação do efeito (pendente, concluído, falhou) para mostrar no pedido e retomada na transação de quem já autorizou |
| Sessão | `GET /api/v1/session/unit` com `membershipId` | O aparelho guarda o rascunho por pessoa na unidade e comanda, sem reaproveitar entre contas |
| Frontend | `orders/tab-orders-page.ts` (`/comandas/:id`), `orders/order-draft.ts`, `orders/orders.service.ts`, botão "Pedido" em `/comandas` | Rascunho no `localStorage` (catálogo por categoria, esgotado sem botão, quantidade, observação, total em centavos); envio com chave e preços vistos; recusa de revisão marca linhas e pede "Confirmar novos preços e enviar"; sem resposta em 20 s guarda a chave, trava a edição, confere a operação e reenvia com a mesma chave; ao voltar à tela com envio pendente, confere antes de tudo; pedidos enviados com o preço de cada envio e "Reenviar à produção" quando falhou |

Nenhuma dependência nova.

## 2. Proposta aprovada por Diego em 03/11/2026 (T-03 da H16, cartão no thread da H16, depois do merge do PR #21)

| Proposta | Conteúdo |
|---|---|
| Observação | Até 140 caracteres, numa linha só, sem cobrança (FR-13) |
| Linhas repetidas | O mesmo produto pode aparecer em várias linhas do mesmo envio, para entregas separadas (FR-19) |
| Espera do envio | A tela espera 20 s; sem resposta, confere a operação pela chave antes de repetir e nunca reenvia sozinha |
| Envios por comanda | Sem limite (cada envio é um pedido confirmado pelo garçom) |

Já fechados em D-13 e aplicados: 50 linhas por envio e 99 unidades por linha.

## 3. Testes

| Teste | Critério da H16 |
|---|---|
| `OrderRulesTest` (4) | Limites exatos (50 linhas, 1–99, 140 caracteres, duas casas) com o campo do erro; esgotado/ausente e preço mudado, um por produto; impressão muda com qualquer campo e com a ordem, sem confundir o separador |
| `OrdersIntegrationTest.enviaComPrecoCapturadoAlocacaoEEncaminhaAProducaoDepoisDoRestart` | Snapshot de nome/preço/setor, total da linha, alocação na comanda, auditoria; **restart entre gravação e produção**: pedido gravado, job pendente, nada na produção; o worker encaminha; rodar de novo não duplica; operação consultável; **preço novo do catálogo não muda o enviado e o mesmo produto aparece com dois preços** |
| `…envioRepetidoComAMesmaChaveNaoDuplica` | **Envio repetido** (mesma chave em sequência e 5 simultâneos): um pedido, um job; mesma chave com outro conteúdo, outra comanda ou outra pessoa: 409; só quem enviou consulta a operação |
| `…precoMudadoOuEsgotadoMantemTudoNoRascunho` | **Preço mudado**: 409 com o preço atual, nada gravado; **esgotado, arquivado e de outra unidade**: 409 com a lista, nada gravado, nenhum job; revisado sai inteiro; pedido anterior não é cancelado quando o produto esgota depois |
| `…fechamentoConcorrenteOuEntraAntesOuRecusaOEnvio` | **Fechamento concorrente com stub contratual**: outra transação trava a comanda (`FOR UPDATE`), o envio espera a trava (conferido em `pg_stat_activity`), a comanda é encerrada e o envio recebe 409 sem gravar; na ordem inversa, o pedido já está alocado quando o fechamento consolida; chave já gravada continua repetível depois do encerramento |
| `…limitesExatosDoEnvio` | **Limites exatos**: 50 linhas ok e 51 recusa; vazio recusa; 99 unidades e 140 caracteres ok; 100, 0, 141 caracteres, quebra de linha, três casas, preço ausente e chave ausente recusam com o campo |
| `…permissaoUnidadeEFalhaDeEncaminhamentoReprocessavel` | Produção 403, sem sessão 401; comanda de outra unidade 404; **falha de encaminhamento visível** (`FALHOU`) e **reprocessada sem outro lançamento**; outra unidade não reprocessa; auditoria |
| `MigrationsIntegrationTest` (3) | V1–V13 em banco vazio e de V1 para V13 |
| `TenantIsolationIntegrationTest` | Tabelas novas com RLS forçado, runtime não é dono |
| `ModularityTest` | Módulos `orders` e `production` sem ciclo nem acesso a internos |
| Frontend `order-draft.spec.ts` (4) | Rascunho por pessoa/comanda, vazio não ocupa armazenamento, conteúdo estranho ignorado, revisão marca e guarda o preço anterior, soma em centavos |
| Frontend `tab-orders-page.spec.ts` (6) | Monta só no aparelho e envia tudo com chave e preços vistos (a chave é guardada antes da resposta); esgotado/preço mudado marcados com reconfirmação; **sem resposta: edição travada, confere a operação e reenvia com a mesma chave**; volta à tela com envio pendente e confere antes de repetir; comanda encerrada preserva o rascunho; falha de encaminhamento e reenvio à produção |

## 4. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B clean verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 da imagem fixada), sessão em nuvem, 03/11/2026 | **356 testes, 0 falhas** (eram 346 na H15); enforcer, Modulith e JaCoCo ok |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 03/11/2026 | lint ok; **147 testes em 29 arquivos, 0 falhas** (10 novos); build ok |
| CI do PR #21 (commit `454b80a`) | GitHub Actions | backend, frontend, imagens e smoke Compose verdes |
| CI de `develop` após o merge (`da4e270`) | GitHub Actions, run 41715851513 | sucesso |

Não executados: `compose-smoke.sh` e build das imagens localmente (rodam na CI do PR); E2E Playwright (não configurado); queda real de rede no celular e teste manual na homologação.
