# H20 — Evidência do atendimento responsável ou central

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V16__atendimento_responsavel_ou_central.sql` | `tab.responsible_id` (quem abre, no garçom responsável; nulo na central; comandas abertas recebem quem abriu); policy de inserção só aceita o próprio autor como responsável. `attendance_call` (tipo, modalidade copiada da comanda, dono na central, conclusão com quem/quando/resolução, versão), índice único parcial de um aviso em aberto por linha, comanda e tipo; RLS sem `DELETE` e `UPDATE` só de dono e conclusão; avisos criados para os itens já prontos ou sinalizados das comandas abertas; tópico `ATENDIMENTO` em `sync_change` |
| Permissão | `identity.Permission.REATRIBUIR_ATENDIMENTO` | Gerente e administrador; não delegável (PRD §4.2: o gerente transfere o atendimento) |
| Comandas | `tabs.Tabs.open`, `Tabs.reassign`, `TabController` | Modalidade da versão da configuração gravada na abertura; responsável inicial; `PUT /api/v1/tabs/{id}/responsible` com versão, só garçom responsável, para alguém ativo que atende; `Tab` e `Label` trazem modalidade e responsável |
| Produção | `production.ProductionLineChanged`, `Production.apply`, `ProductionIntake` | Evento publicado na transação da mudança: pronto (inclusive sem preparo, no job), entregue, impossível, sinalização retirada. `linesInCurrentUnit` para o atendimento ler o item. Responsável da fila = responsável da comanda ou "Central" |
| Atendimento | módulo `attendance`: `Attendance`, `ProductionCalls`, `domain.CallRules`, `JdbcAttendanceStore`, `AttendanceController` | Avisos no mesmo commit da produção; fila de todos os chamados em aberto (com você, central sem dono, de outras pessoas); assumir exclusivo na central (chamado travado `FOR UPDATE`); reatribuição do gerente com versão; auditoria |
| Frontend | `attendance/` (`/atendimento`), `tabs/tabs-page.ts`, `live/chime.ts`, `production/production-page.ts`, início | Entregar (também o de outra pessoa), assumir, reatribuir; responsável e "Trocar responsável" nas comandas; som opcional por aparelho, desligado por padrão; sinal visual de novo; sem botão "Chamar atendimento" |

Nenhuma dependência nova.

## 2. T-03 da H20, aprovada como proposta em 04/11/2026: Diego delegou a decisão ("pode decidir tbm", thread da H21) e foi adotada a opção recomendada no cartão do thread da H20

| Proposta | Valor |
|---|---|
| Quem reatribui | Permissão nova `REATRIBUIR_ATENDIMENTO`: gerente e administrador, não delegável |
| Para quem | Qualquer pessoa ativa da unidade que atende comandas (garçom, gerente, administrador) |
| Ajuda | Todos que atendem veem todos os chamados e podem entregar o item de outra pessoa; na central, assumir é exclusivo, mas não trava a entrega |
| Devolver à central | Quem assumiu não devolve; só o gerente reatribui |
| Granularidade | Um aviso por item e comanda de destino |
| Impossibilidade | Termina quando a produção retira a sinalização ou o item é cancelado (H23); quem atende não dispensa |
| Troca de responsável | Leva junto os chamados em aberto da comanda |
| Fila de produção | Comanda da central mostra "Central" como responsável |
| Som | Opcional, desligado por padrão, em cada aparelho, na produção e no atendimento |
| Leitura | Até 200 chamados, dos mais antigos aos mais novos |

## 3. Testes

| Teste | Critério da H20 |
|---|---|
| `AttendanceIntegrationTest.garcomResponsavelRecebeOsAvisosEOutrosAjudam` | Modalidade capturada na abertura e responsável inicial; item sem preparo gera aviso no job; **ler não conclui**; ninguém assume no garçom responsável; produção não vê a fila; impossibilidade abre aviso e a retirada conclui (`SINALIZACAO_RETIRADA`, por quem); **ajuda**: outro garçom entrega e o aviso conclui (`ENTREGUE`, por quem entregou); pronto pela produção abre novo aviso; tópico `ATENDIMENTO` |
| `…gerenteReatribuiOResponsavelEOsAvisosAcompanham` | **Reatribuição**: garçom 403; produção e pessoa de outra unidade 400; sem versão 400; versão antiga 409; os avisos em aberto passam ao novo responsável; auditoria |
| `…centralTemClaimExclusivoETrocarModalidadeNaoMigraChamadosAntigos` | **Troca de modo sem migrar**: comanda antiga continua no garçom responsável, nova vai para a central sem dono; **dois funcionários assumem ao mesmo tempo**: um 200, outro 409 com quem assumiu; assumir de novo o próprio não muda nada; gerente reatribui com versão (garçom 403, versão antiga 409, produção 400); chamado do garçom responsável não é reatribuído por chamado e comanda da central não tem responsável; quem não assumiu também entrega; concluído não muda mais; "Central" na fila de produção |
| `…outraUnidadeNaoVeNemAssumeEORuntimeNaoApagaChamados` | Outra unidade não vê nem assume (404); runtime sem contexto não lê, não apaga e não muda o tipo |
| `CallRulesTest` (4) | Regras puras de assumir e reatribuir |
| `AccessPolicyTest`, `MigrationsIntegrationTest` | Matriz com a permissão nova; 16 migrations |
| `attendance-page.spec.ts` (6), `tabs-page.spec.ts` (1 novo), `chime.spec.ts` (1), `unit-home-page.spec.ts` | Grupos e contadores, sem botão de chamar atendimento; assumir com recusa de quem assumiu antes; ajuda na entrega com a versão; reatribuição na central e no garçom responsável; aviso relê, sinal de novo e som; produção sem acesso; responsável nas comandas e troca; som desligado por padrão e guardado no aparelho |

## 4. Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw -B clean verify` (backend) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Testcontainers PostgreSQL | branch `feature/H20-atendimento-responsavel-central` (antes do commit) | 384 testes, 0 falhas; BUILD SUCCESS (JaCoCo, Modulith, enforcer) |
| `./scripts/verify.sh frontend` e depois `npm run lint` + `npm run test:ci` + `npm run build` | Contêiner `node:24.18.0-trixie-slim`, npm 11.16.0 | idem | lint sem erros, 179 testes (34 arquivos), linhas 95,01% / branches 87,33%, build ok |

Não executados: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); teste manual com dois aparelhos na homologação de Diego.
