# H07 — Evidência de identidade, vínculo e seleção de unidade

Registro sanitizado. Claude Code, 11/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas de banco geradas a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Identidade global | `V4__identidade_e_vinculos.sql`: `user_identity` | Uma pessoa, várias unidades. E-mail único em minúsculas, nome, papel global opcional (`ADMIN_FASTPAY`). Sem credencial nem MFA (H08/H09). Runtime lê só a própria identidade (`app.identity_id`) |
| Vínculo | `membership` | `establishment_id NOT NULL`, papel (`ADMIN_ESTABELECIMENTO`, `GERENTE`, `GARCOM`, `PRODUCAO`), `ACTIVE`/`INACTIVE`, autor e data da desativação. Índice único parcial: um vínculo ativo por pessoa e unidade; antigos ficam como histórico. Runtime sem INSERT/DELETE e com UPDATE só das colunas de desativação |
| RLS | policies de `membership` e `establishment` | Com unidade no contexto: só vínculos dela (admin de uma loja não vê vínculos em outras). Sem unidade e com identidade: só os próprios vínculos e o nome das próprias unidades. Sem contexto: nada |
| Contexto de identidade | `tenancy.TenantTransactions.asIdentity` | Mesmo padrão de `inEstablishment`: `set_config(..., true)` por transação, descartado no commit/rollback |
| API pública | `identity.Memberships`, `SessionContext`, `UnitMembership`, `MembershipRole`, exceções | `inUnit(identidade, unidade, trabalho)` abre a transação da unidade e revalida o vínculo ativo nela antes do trabalho; `activeUnits`; `deactivate` com auditoria `vinculo.desativado` |
| HTTP | `identity.web.SessionUnitController`, `IdentityProblems` | `GET /api/v1/session/units`, `PUT/GET/DELETE /api/v1/session/unit`. Problemas `SESSAO_AUSENTE` (401), `UNIDADE_NAO_AUTORIZADA` (403, mesma resposta para unidade alheia, inexistente ou desativada) e `UNIDADE_NAO_SELECIONADA` (409), mensagens em português |
| Frontend | `src/app/session/*`, `app.ts`, rotas `/unidades` e `/inicio` | Tela de escolha (entra direto com um único vínculo), unidade ativa e papel sempre no cabeçalho com "Trocar unidade", limpeza registrada (`onUnitCleared`) ao trocar ou perder a unidade, interceptor que volta à seleção quando o servidor recusa a unidade, guard só de navegação |

A unidade escolhida fica na sessão do servidor, nunca em header, parâmetro ou armazenamento do navegador. Escolher unidade troca o id da sessão. A identidade da sessão é gravada pelo login, que chega em H08: até lá a API responde 401 fora dos testes.

## 2. Testes

| Teste | Critério da H07 |
|---|---|
| `MembershipsIntegrationTest.mesmaIdentidadeTemPapeisDistintosPorUnidade` | Identidade com papéis distintos em duas lojas |
| `…unidadeSemVinculoInexistenteOuNulaEhRecusadaDoMesmoJeito` | ID forjado ou inexistente recusado |
| `…trabalhoNaUnidadeSoEnxergaDadosDela` | Consultas limitadas à unidade do vínculo |
| `…administradorDeUmaLojaNaoVeVinculosEmOutras` | FR-03, visibilidade entre lojas; identidade sem unidade só vê o que é seu |
| `…semContextoNenhumVinculoOuIdentidadeEhVisivel` | Fail-closed |
| `…acessoGlobalNaoConcedeOperacaoDeLoja` | Admin fastPay sem vínculo não opera loja |
| `…desativarVinculoRevogaSoAquelaUnidadeEPreservaHistorico` | Desativação preserva linha, autor, auditoria e o vínculo da outra loja |
| `…desativarVinculoDeOutraUnidadeNaoTemEfeito`, `…novoVinculoDepoisDeDesativadoMantemOAntigoComoHistorico`, `…runtimeNaoCriaNemApagaVinculos` | Isolamento da escrita, unicidade do vínculo ativo, sem DELETE/INSERT/troca de papel pelo runtime |
| `SessionUnitControllerTest` (12) | API: sem sessão 401; lista com papéis; escolha troca id da sessão; troca de unidade limita consultas ao novo vínculo; unidade alheia e inexistente com resposta idêntica e sem nome; ID de outra loja em caminho e header ignorado; vínculo revogado com unidade escolhida perde acesso na operação seguinte e a escolha é esquecida; admin fastPay recusado; sair exige nova escolha; corpo inválido 400 |
| `MigrationsIntegrationTest` (atualizado) | Banco vazio até V4 e atualização V1 → V4 |
| Frontend `unit-selection-page.spec.ts` (10) | Carregando em região de status, lista com papel, unidade atual, escolha, entrada direta com um vínculo, recusa durante a escolha (sem entrada automática depois), falha com nova tentativa, sem vínculos, sem sessão, erro de conexão |
| `unit-context.service.spec.ts` (5), `unit-context.interceptor.spec.ts` (3), `unit-selected.guard.spec.ts` (3), `unit-home-page.spec.ts` (1), `app.spec.ts` (3) | Limpeza do contexto ao trocar/sair/perder unidade, rótulos de papel em português, unidade ativa visível e troca pelo cabeçalho mesmo sem resposta do servidor |

### 2.1 Execução local

Backend, da raiz, no contêiner da CI (`eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc…`) com `unzip`, socket do Docker para o Testcontainers (PostgreSQL 18.6, mesmo digest) e o proxy/CA da sessão: `./mvnw -B -ntp clean verify`. Base antes da mudança, `develop` `4bed847`: 39 testes, BUILD SUCCESS. Com a H07 (commit `c5de5a5`):

```
Tests run: 12, Failures: 0 -- in tech.malyah.fastpay.identity.web.SessionUnitControllerTest
Tests run: 10, Failures: 0 -- in tech.malyah.fastpay.identity.MembershipsIntegrationTest
Tests run: 12, Failures: 0 -- in tech.malyah.fastpay.jobs.JobsIntegrationTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.tenancy.MigrationsIntegrationTest
Tests run: 9, Failures: 0 -- in tech.malyah.fastpay.tenancy.TenantIsolationIntegrationTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.ModularityTest
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Módulos reconhecidos pelo Modulith: `platform`, `tenancy`, `establishments`, `jobs`, `identity`. O teste de RLS da H03 confirma que `user_identity` e `membership` também têm RLS forçado.

Frontend no contêiner da CI (`node:24.18.0-trixie-slim@sha256:ae91dcc1…`, npm 11.16.0): `./scripts/verify.sh frontend` com exit 0. Lint sem erros, 7 arquivos e 28 testes Vitest aprovados (linhas 99,41% — 171/172, branches 94,11%), build de produção ok.

Primeira execução: backend com um erro de compilação (variável capturada em lambda) e frontend com 6 falhas, todas de teste ou de texto (espaço entre nome e papel no texto acessível do botão; entrada automática depois de uma recusa, que passou a não acontecer). Corrigidas antes do commit.

## 3. Limites desta história

- Login, sessão JDBC, CSRF e expiração: H08. Até lá nenhum fluxo real grava a identidade na sessão; os testes a gravam diretamente.
- Criar vínculos (convites) e o comando HTTP de desativar com checagem de permissão: H10/H11. `Memberships.deactivate` existe e é testado; quem chamar autoriza o ator, como em `Jobs.retryExhausted`.
- Permissões por ação: H11. O papel é retornado e exibido, ainda não decide ações.
- E2E Playwright: ainda não configurado no projeto (planejado).
