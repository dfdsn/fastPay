# H21 — Evidência dos problemas do consumidor e conclusão autorizada

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V17__problemas_do_consumidor.sql` | `consumer_issue` (comanda, item ou conta, descrição, modalidade copiada, após encerramento, acesso que informou, estado, quem atende, encaminhamento, conclusão com resolução e correção, versão); índices únicos parciais de um aberto por item e um da conta; RLS: consumidor sem identidade só insere aberto, equipe só atualiza o não tratado, sem `DELETE`; tópico `PROBLEMA` em `sync_change` |
| Regras | `attendance.domain.IssueRules` | Estados sem volta; atender exclusivo na central e com ajuda no garçom responsável; reatribuir só na central com versão; encaminhar e concluir só depois de atender; caso após encerramento só com quem autoriza devolução; textos de 5 a 300 |
| Serviço | `attendance.ConsumerIssues`, `JdbcIssueStore` | Lado do cliente com o acesso conferido na mesma transação (`GuestAccess.inTab`); lado da equipe com permissão lida na transação do comando e problema travado `FOR UPDATE`; auditoria; aviso `PROBLEMA`; contrato `blocksChargeInCurrentUnit` para H28/H30 |
| API | `GuestIssueController` (`/api/v1/guest/tabs/{id}/issues`), `AttendanceController` (`/api/v1/attendance/issues/*`, `issue-attendants`, fila com `issues`), `Responses` | Códigos e mensagens em português; respostas de cliente sem acesso iguais às da H15 |
| Apoio | `tabs.GuestCookie`, `GuestAccess.inTab`, `Orders.itemsOfTabInCurrentUnit` | Ler o cookie do consumidor fora do módulo `tabs`; itens da comanda sem valores para o cliente apontar |
| Frontend | `guest/guest-issues.ts` (na comanda do cliente), `attendance/issues-panel.ts` (em `/atendimento`) | Cliente aponta item ou conta, acompanha estado e resolução; equipe atende, encaminha, conclui (correção + resolução) e o gerente reatribui na central; avisos `PROBLEMA` relêem as telas |

Nenhuma dependência nova.

## 2. Proposta aguardando Diego (T-03 da H21)

| Proposta | Valor |
|---|---|
| Textos | Descrição do cliente e resolução de 5 a 300 caracteres |
| Duplicidade | Um problema em aberto por item e um da conta, por comanda; depois de tratado, pode abrir outro |
| O que o cliente vê | Itens enviados com produto e quantidade, sem valores (valores com a H22); estado e resolução dos seus problemas, sem nomes da equipe |
| Quem atende | `ATENDER_PROBLEMA`: garçom, gerente, administrador (produção não) |
| Central | Atender é exclusivo; só o gerente reatribui (como na H20) |
| Garçom responsável | Quem começa fica registrado; os demais ajudam; o dono é o responsável da comanda |
| Quem conclui | `CONCLUIR_PROBLEMA`: gerente e administrador; garçom só com delegação |
| Para concluir | Alguém já começou o atendimento; resolução escrita; "nada a corrigir" ou "a correção já foi feita" |
| Encaminhar | Marca "encaminhado ao gerente" visível na fila, sem trocar o dono |
| Após encerramento | Nasce encaminhado; só quem autoriza devolução (gerente, administrador) conclui |
| Em nome do cliente | A equipe não abre problema pelo cliente (sem celular, a ajuda é presencial, FR-26) |
| Leitura | Até 200 problemas não tratados, dos mais antigos aos mais novos |

## 3. Testes

| Teste | Critério da H21 |
|---|---|
| `ConsumerIssuesIntegrationTest.clienteInformaEquipeAtendeEConcluiComResolucao` | Cliente vê os itens sem valores; descrição curta 400; item e conta; repetição 409; **cobrança bloqueada** com problema aberto e liberada só depois do último tratado; fila com dono; **tratar sem permissão** (garçom 403) e encaminhar; concluir antes de atender 409; resolução e correção obrigatórias; versão antiga 409; **tratado não altera valores** (linhas, preços e alocações iguais); tratado não muda mais; delegação vale na sessão aberta; novo problema do mesmo item depois de tratado; auditoria |
| `…correcaoNaComandaNaoConcluiOProblema` | **Correção sem concluir o chamado**: mesa trocada, item preparado e entregue, e o problema continua em atendimento e bloqueando cobrança; produção 403 |
| `…clienteDeOutraComandaNaoVeNemInforma` | **Cliente de outra comanda**: 404 igual ao de comanda inexistente; sem cookie 404; item de outra comanda 404; acesso revogado deixa de valer; outra unidade não vê nem atende |
| `…centralAtendimentoExclusivoEReatribuicaoPeloGerente` | Dois atendem ao mesmo tempo: um 200, outro 409 com quem atende; garçom não reatribui; produção não recebe; gerente reatribui |
| `…garcomResponsavelNaoTemReatribuicaoDeProblema` | Ajuda no garçom responsável; reatribuição por problema recusada (troca-se o responsável da comanda) |
| `…problemaDepoisDoPagamentoPreservaQuitacaoEVaiAoGerente` | **Caso pago encaminhado a gerente**: comanda encerrada no banco (encerramento real é da H28–H32); problema nasce encaminhado; garçom com delegação recebe 403 `PROBLEMA_EXIGE_GERENTE`; gerente conclui; `closed_at`, `access_until` e versão da comanda iguais |
| `…runtimeNaoApagaNemReescreveProblemas` | Runtime sem contexto não lê, não apaga e não reescreve a descrição |
| `IssueRulesTest` (6) | Regras puras |
| `AttendanceIntegrationTest`, `MigrationsIntegrationTest` | Fila da H20 continua igual; 17 migrations |
| `guest-issues.spec.ts` (3), `issues-panel.spec.ts` (3), `guest-tab-page.spec.ts` | Itens e problemas do cliente; informar com recusa do servidor; texto curto não enviado; conta encerrada; atender, encaminhar, concluir com correção, versão desatualizada relê, caso pago e reatribuição |

## 4. Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw -B clean verify` (backend) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Testcontainers PostgreSQL | branch `feature/H21-problemas-consumidor` (antes do commit, sobre `c06b7b8`) | 397 testes, 0 falhas; BUILD SUCCESS (JaCoCo, Modulith, enforcer) |
| `./scripts/verify.sh frontend` | Contêiner `node:24.18.0-trixie-slim`, npm 11.16.0 | idem | lint sem erros, 185 testes (36 arquivos), linhas 95,19% / branches 87,51%, build ok |

Não executados: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); teste com o celular do cliente na homologação de Diego. "Cobrança em andamento" depende da cobrança real (H30): aqui só o contrato que ela chamará.
