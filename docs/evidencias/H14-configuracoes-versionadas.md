# H14 — Evidência de configurações versionadas por estabelecimento

Registro sanitizado. Claude Code, 03/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, chaves e senhas de banco geradas a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V11__configuracao_versionada.sql` | `unit_settings_version` (uma linha imutável por versão; runtime só `SELECT`/`INSERT`; `CHECK` dos limites), `operational_calendar` (fuso, corte, `effective_from`, `first_date`, cancelamento só antes de valer), `membership.discount_limit_percent`. RLS `ENABLE + FORCE`. Gatilho `AFTER INSERT` em `establishment` grava a versão 1 e o calendário padrão; as unidades já existentes recebem os mesmos padrões na migração |
| Regras puras | `establishments.domain.SettingsRules`, `establishments.domain.CalendarRules` | Validação campo a campo sem arredondar; dia operacional por fuso e corte; início da mudança no próximo dia e dia de transição sem repetir nem pular data |
| Serviço e API | `establishments.EstablishmentSettings`, `establishments.web.SettingsController` (`/api/v1/establishment/settings`) | Ver com `VER_CONFIGURACAO` (gerente e admin), salvar com `CONFIGURAR_ESTABELECIMENTO` (admin) e versão esperada, unidade travada; pontos de captura para as comandas |
| Limite de desconto (P-17) | `identity.domain.AccessPolicy`, `identity.Team.setDiscountLimit`, `identity.web.TeamController` (`/api/v1/team/members/{id}/discount-limit`), `UnitMembership.discountLimitPercent` | Por pessoa, padrão por papel, gerente até o próprio limite, efeito na operação seguinte |
| Frontend | `settings/settings-page.ts`, `settings/settings.service.ts`, rota `/configuracao`, link no início; `team/team-page.ts` | Admin edita, gerente consulta; recusa do servidor no campo; conflito de versão com "Recarregar"; mudança de corte agendada visível; dia operacional na hora local da unidade; limite por pessoa na Equipe |

## 2. Propostas aprovadas por Diego em 03/11/2026 (thread da H14, depois do merge do PR #17)

| Proposta | Conteúdo |
|---|---|
| T-03 (H14) | Serviço 0–20% (maior que zero se habilitado), percentual com 4 casas; couvert R$ 0,01–200,00; corte 00:00–12:00; fuso IANA de região. Padrões: serviço 10% após desconto sem couvert, couvert desligado, desconto em reais e percentual, garçom responsável, saída automática, America/Sao_Paulo às 06:00 |
| P-17 | Limite de desconto por pessoa: admin 100% fixo; gerente 100% até o admin mudar; garçom 0% até gerente/admin mudar (gerente no máximo até o próprio); produção sem limite; trocar o papel volta ao padrão. Alternativa: por papel |

Vigência adotada (D-08, FR-57): serviço, couvert automático, atendimento e saída capturados na abertura da comanda; couvert manual e formatos de desconto conferidos na hora; fuso e corte só no próximo dia operacional.

## 3. Testes

| Teste | Critério da H14 |
|---|---|
| `SettingsRulesTest` (6) | Padrões válidos; serviço 0–20 com até 4 casas, zero só desabilitado, 5 casas recusadas; couvert obrigatório quando ligado, 0, acima de 200 e 3 casas recusados; todos os campos obrigatórios e escolhas inválidas; partes alteradas para a auditoria |
| `CalendarRulesTest` (6) | Madrugada antes do corte no dia anterior; corte 06→08 (dia de 26 h) e 06→04 (22 h); troca de fuso São Paulo→Manaus (25 h); dia atual termina quando a mudança agendada começa; fuso/corte válidos e recusados |
| `AccessPolicyTest` (30) | Matriz com `VER_CONFIGURACAO` (gerente e admin); limite de desconto por papel e quem pode mudar |
| `SettingsIntegrationTest.unidadeNasceComOsPadroesEGerenteConsultaSemEditar` | Versão 1 com os padrões; gerente vê sem editar e recebe 403 ao salvar; garçom e produção 403 |
| `…salvarCriaNovaVersaoEAComandaCapturadaAntesContinuaComAVersaoAntiga` | **Snapshot antes/depois:** versão capturada antes da mudança relida intacta depois; captura nova pega a versão 2; outra unidade não lê a versão; mesmos valores não criam versão; auditoria `v2: servico,couvert,saida` |
| `…versaoDesatualizadaEConfiguracaoInvalidaSaoRecusadas` | **Limite percentual e configuração inválida recusados** pela API (com `field` e `problems`) e pelo `CHECK` do banco; versão desatualizada 409; sem permissão 403 antes de validar |
| `…permissaoAlteradaValeNaOperacaoSeguinte` | **Permissão imediata:** gerente rebaixado perde a leitura na requisição seguinte; promovido a admin salva |
| `…mudancaDeCorteValeSoNoProximoDiaOperacional` | Corte agendado para o início do próximo dia (06:00 local); substituir e cancelar antes de valer; depois de valer, hoje mantém a classificação antiga e o dia seguinte usa o corte novo; nova mudança vira pendente |
| `…runtimeNaoAlteraVersaoGravadaNemLeSemContexto` | RLS sem contexto; `UPDATE`/`DELETE` de versão negados ao runtime |
| `TeamIntegrationTest.limiteDeDescontoPorPessoaValeNaOperacaoSeguinte` | Padrões por papel; admin limita gerente; gerente dá até o próprio limite ao garçom e é recusado acima; recusas de próprio vínculo, admin, produção e garçom; valores fora de 0–100 ou com 5 casas; efeito imediato em `UnitMembership`; troca de papel volta ao padrão; auditoria antes/depois |
| `EstablishmentOnboardingIntegrationTest` | Unidade cadastrada pelo runtime nasce com a versão 1 e o calendário padrão (gatilho com RLS) |
| `MigrationsIntegrationTest` (3) | V1–V11 em banco vazio, de V1 para V11 e unidade cadastrada antes da V11 recebendo os padrões, com o `FORCE` de `establishment` restaurado |
| Frontend `settings-page.spec.ts` (6), `team-page.spec.ts` (+1) | Padrões e dia operacional na hora local; salvar com versão e mostrar a nova; erro por campo; conflito e recarga; gerente só consulta; mudança agendada; limite de desconto do garçom, recusa acima do gerente e sucesso |

## 4. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B clean verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 da imagem fixada), sessão em nuvem, 03/11/2026 | **323 testes, 0 falhas**; enforcer, Modulith e JaCoCo ok |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 03/11/2026 | lint ok; **125 testes**, 0 falhas; build ok (aviso NG8102 já existente em `team-page.ts`) |

Não executados: `scripts/compose-smoke.sh` e build das imagens (rodam na CI do PR; sem mudança de Dockerfile/Compose); E2E Playwright (não configurado); teste manual na homologação da máquina de Diego.
