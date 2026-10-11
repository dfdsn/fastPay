# Progresso — fastPay

Atualizado em 08/10/2026. Responsável pelo aceite: Diego.

## Situação real

Atualizado em 11/10/2026 por Claude Code (H01–H03 reconciliadas; H04 em revisão).

**Correção de registro (08/10/2026):** até 08/10/2026 o repositório local não tinha nenhum commit, embora esta seção dissesse que `main`, `develop` e `feature/H01-bootstrap` existiam. A chave GPG de Diego (assinatura obrigatória) estava bloqueada. Depois de desbloqueada, os commits foram criados: pacote documental em `main` (e `develop`), H01 em `feature/H01-bootstrap` e H02 em `feature/H02-ambientes-docker-ci`, criada a partir da H01 porque a H01 ainda não está em `develop`.

**Troca de história:** em 08/10/2026 Diego pediu “Implemente a próxima história elegível” com a H01 aguardando validação. Tratei o pedido como a autorização explícita de troca exigida pelo AGENTS. A H01 continua aguardando validação.

**Remoto:** `https://github.com/dfdsn/fastPay` desde 08/10/2026. Branches `main`, `develop`, `feature/H01-bootstrap` e `feature/H02-ambientes-docker-ci` publicadas em 08/10/2026.

**PR #1 (11/10/2026, reconciliação):** [dfdsn/fastPay#1](https://github.com/dfdsn/fastPay/pull/1) (H01 + H02, base `develop`) foi mergeado por Diego; merge `216002c` em `develop`. A CI desse PR (run 37806871793) e a do push em `develop` (run 37828730825) **falharam** no job de backend: `Failed to validate Maven distribution SHA-256`. O frontend passou nas duas. Causa: sem `unzip` (caso da imagem Temurin e do WSL) o `mvnw` troca o `.zip` pelo `.tar.gz`, e o checksum fixado é o do `.zip` (conferido de novo contra o SHA-512 oficial em 11/10/2026). Reproduzido no WSL Ubuntu com clone limpo de `216002c`. O job de imagens e smoke Compose ficou `skipped` e nunca rodou. Correção na branch `feature/H02-corrige-ci-maven`.

**PR #2 (11/10/2026):** [dfdsn/fastPay#2](https://github.com/dfdsn/fastPay/pull/2) corrige a CI em dois pontos: `unzip` para o Maven Wrapper e healthcheck do backend sem `curl` (a imagem JRE não tem `curl`). CI verde no commit `ebb6871` (run 38105381291): backend, frontend, imagens e smoke Compose. Evidência em [evidencias/H02-ambientes.md](evidencias/H02-ambientes.md) §3.3–3.4.

Histórias aguardando revisão: **H01 e H02** (PR #2 aberto, CI verde). Próximo passo delas: Diego revisa e faz o merge do PR #2; depois, conferir a CI do push em `develop` e a publicação das imagens no GHCR (privadas) e registrar H01/H02 como concluídas com o hash real.

**Troca para H03 (11/10/2026):** Diego pediu “Siga com o desenvolvimento” com o PR #2 ainda aberto. Tratei como autorização para a próxima história. A H03 foi feita em `feature/H03-migrations-rls`, criada a partir de `feature/H02-corrige-ci-maven`, porque precisa da CI corrigida. **H03 aguardando revisão** no [PR #3](https://github.com/dfdsn/fastPay/pull/3): CI verde em `0d10270` (run 38165969878), com 17 testes, incluindo os de isolamento e migração em PostgreSQL real, e o smoke Compose. Próximo passo: Diego faz o merge do PR #2 e depois do PR #3; então conferir a CI em `develop` e registrar H01–H03 como concluídas com os hashes reais.

**Reconciliação após merge (11/10/2026):** Diego fez o merge do [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`, 19:05 UTC) e do [PR #3](https://github.com/dfdsn/fastPay/pull/3) (`96ac6bf`, 19:19 UTC) em `develop`. CI do push em `develop`: run 38166528466 em `33dfc80` e run 38167523089 em `96ac6bf`, ambas verdes (backend, frontend, imagens e smoke Compose). Com isso **H01, H02 e H03 estão concluídas**. A visibilidade privada dos pacotes no GHCR não pôde ser conferida pela API desta sessão (T-02, Diego confere).

**H04 (11/10/2026):** Diego pediu “Implemente a próxima história elegível”. Sem história em revisão, a menor elegível é a H04 (depende só da H03; H05 depende de acesso ao PSP). Feita em `feature/H04-eventos-duraveis-jobs`, a partir de `develop` (`96ac6bf`). **Aguardando revisão** no [PR #4](https://github.com/dfdsn/fastPay/pull/4), CI verde; detalhes no registro da H04.

## Estados

Não iniciada → em andamento → aguardando validação ou revisão → concluída. Bloqueada quando falta pré-requisito concreto. Aguardando revisão inclui PR aberto; concluída só após checks e merge develop verificados. Uma release possui registro separado do estado das histórias. Progresso inicialmente sem agente/branch/commit/PR.

## Histórias

| ID | História | Estado | Evidência / próximo passo |
|---|---|---|---|
| H01 | Bootstrap reproduzível e contratos de qualidade | Concluída | Merge em `develop` pelo [PR #1](https://github.com/dfdsn/fastPay/pull/1) (`216002c`) e correção da CI no [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`); CI de `develop` verde (run 38166528466) ([evidência](evidencias/H01-build.md)) |
| H02 | Ambientes Docker e pipeline GitHub | Concluída | [PR #1](https://github.com/dfdsn/fastPay/pull/1) + [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`); CI de `develop` verde com imagens e smoke (run 38166528466) ([evidência](evidencias/H02-ambientes.md)); visibilidade do GHCR a conferir (T-02) |
| H03 | Migrations e fundação de isolamento RLS | Concluída | [PR #3](https://github.com/dfdsn/fastPay/pull/3) (`96ac6bf`); CI de `develop` verde (run 38167523089) ([evidência](evidencias/H03-migrations-rls.md)) |
| H04 | Eventos duráveis, idempotência e jobs | Aguardando revisão | [PR #4](https://github.com/dfdsn/fastPay/pull/4), CI verde com PostgreSQL real ([evidência](evidencias/H04-eventos-jobs.md)); ver registro abaixo |
| H05 | Prova PSP: recebedor, cobrança e split | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H06 | Prova PSP: devolução, conciliação e mensalidade | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H07 | Identidade, vínculo e seleção de unidade | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H08 | Login da equipe e sessões seguras | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H09 | TOTP, códigos e bootstrap administrativo | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H10 | Criar estabelecimento e convidar dono | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H11 | Equipe, permissões e concessão de suporte | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H12 | Convites e e-mail durável em homologação | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H13 | Catálogo, setores, disponibilidade e logo | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H14 | Configurações versionadas por estabelecimento | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H15 | Comanda individual, visita e QR avulso | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H16 | Rascunho, envio atômico e preço capturado | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H17 | Sincronização operacional SSE e reconexão | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H18 | Fila de produção e entrega por linha | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H19 | Operações em lote de produção | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H20 | Atendimento responsável ou central | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H21 | Problemas do consumidor e conclusão autorizada | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H22 | Composição, serviço e descontos exatos | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H23 | Cancelar quantidade e alertar produção | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H24 | Desfazer entrega com histórico | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H25 | Transferir unidades sem duplicar produção | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H26 | Continuação de visita e ajustes de couvert | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H27 | Google opcional e histórico do consumidor | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H28 | Conferência, bloqueio e reabertura | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H29 | Encerramento de conta zerada | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H30 | Pagamento integrado e reconciliação de incerteza | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H31 | Recebimento externo e invalidação | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H32 | Saída automática ou validada | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H33 | Comprovante não fiscal em PDF | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H34 | Encerrar débito e regularizar depois | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H35 | Devolução integrada atribuída ou livre | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H36 | Devolução externa com evidências | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H37 | Conciliação financeira e chargeback | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H38 | Planos e condições com vigência | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H39 | Checklist e ativação pelo admin fastPay | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H40 | Mensalidade Pix e comissão por pagamento | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H41 | Suspensão, cancelamento e reativação | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H42 | Dashboard financeiro e calendário operacional | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H43 | Métricas operacionais e ranking local | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H44 | Catálogo de referência da rede | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H45 | Exportações CSV assíncronas | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H46 | Indicadores da rede e exportação protegida | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H47 | Privacidade, exclusão e retenção | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H48 | Contingência manual e procedimento de suporte | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H49 | S3 privado e SES produtivo | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H50 | Deploy manual Hostinger, domínio e tunnel | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H51 | Backup PITR e restauração cronometrada | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H52 | Monitoramento e alertas externos | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H53 | Jornadas, segurança, acessibilidade e carga | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H54 | Aceite e liberação do piloto acompanhado | Não iniciada | Sem evidência; ver dependências/gates no backlog |

## Registro de execução (preencher por história)

- História e estado:
- Data/hora e agente (Codex ou Claude Code):
- Objetivo e critérios atendidos:
- Critérios faltantes / motivo:
- Branch e commit reais:
- PR, checks e merge real (se existente):
- Arquivos/migrations/contratos alterados:
- Testes: comando, ambiente, resultado, artefato/link e commit testado:
- Testes não executados e por quê:
- Decisões novas/alteradas (IDs e aprovação):
- Bloqueios / quem resolve / gate:
- Próximo passo concreto:


### H01 — Bootstrap reproduzível e contratos de qualidade

- História e estado: H01, **aguardando validação** (critérios atendidos localmente; sem remoto, PR, checks de CI ou merge).
- Data/hora e agente: 07/10/2026, ~13:15–14:00 (BRT), Claude Code.
- Objetivo e critérios atendidos:
  - Backend mínimo Java 25 / Boot 4.1.1 / Modulith 2.1.1 com Maven Wrapper 3.3.4 (Maven 3.9.16, SHA-256 fixado) e enforcer de versões.
  - Frontend Angular 22.0.8 standalone/Signals/Material 22.0.7 com `package-lock.json` e versões exatas; npm 11.16.0 exigido.
  - Tela “Situação do sistema” (carregando/sucesso/erro com nova tentativa, região `status`, foco por teclado) consumindo `GET /api/v1/platform/info`; saúde em `GET /actuator/health` (somente status). Sem regra comercial.
  - Lint (ESLint + angular-eslint com regras de acessibilidade de template), Vitest com cobertura, JUnit, JaCoCo com gate 80/70 em domain/application e teste de fronteiras Modulith com prova de que o gate detecta violação.
  - README executável, `scripts/verify.sh`, `.env.example` com as variáveis efetivas, tabela de comandos do AGENTS atualizada.
  - Versões e incompatibilidades registradas em [evidencias/H01-build.md](evidencias/H01-build.md); T-01 marcado como parcial em decisões.
- Critérios faltantes / motivo:
  - “Scripts CI executáveis”: o script roda localmente; a execução em CI depende do remoto GitHub e da pipeline de H02.
  - “Build limpo em ambiente novo”: feito sem artefatos do projeto, mas com caches `~/.m2`/npm já populados e no Windows, não em WSL2 (WSL sem rede nesta máquina). Prova em máquina nova fica para a CI.
  - PIT e Playwright não configurados: não fazem parte do aceite de H01 (entram na primeira regra crítica e na história de E2E).
- Branch e commit reais: `feature/H01-bootstrap` (a partir de `develop`), commit `403c606`, criado em 08/10/2026 depois do desbloqueio da chave GPG.
- PR, checks e merge real: nenhum; não existe remoto.
- Arquivos alterados: `backend/**`, `frontend/**`, `scripts/verify.sh`, `.env.example`, `.gitignore`, `.gitattributes`, `README.md`, `AGENTS.md` (comandos), `docs/especificacao-tecnica.md` (estado/versões), `docs/decisoes-pendentes.md` (T-01), `docs/evidencias/H01-build.md`, este arquivo. Sem migrations nem contratos de negócio.
- Testes:
  - `./scripts/verify.sh` (Git Bash, Windows 11 10.0.26200, Temurin 25.0.4.1+1, Node 24.18.0, npm 11.16.0), 07/10/2026 13:44 BRT, a partir de estado sem `target`/`node_modules`: **exit 0**. Backend: 6 testes, 0 falhas, enforcer e JaCoCo ok, BUILD SUCCESS. Frontend: lint sem erros, 4 testes Vitest aprovados (linhas 100%, branches 94,28%), build de produção ok. Saídas em [evidencias/H01-build.md](evidencias/H01-build.md).
  - Smoke manual com `java -jar` + `ng serve`: health 200 UP, info via proxy 200, `/actuator/env` 404, tela com sucesso e com backend parado (mensagem em português, foco no botão).
  - Testado na árvore de trabalho que compõe o commit da H01 (sem alterações de código depois da execução; somente documentação).
- Testes não executados e por quê: CI (sem remoto); execução em WSL2 (sem rede no WSL); E2E Playwright e PIT (fora do escopo de H01).
- Decisões novas/alteradas: nenhuma decisão de produto. Escolhas técnicas rotineiras: pacote base `tech.malyah.fastpay` (domínio `malyah.tech`); módulo inicial `platform` só com identificação da aplicação; fonte do sistema no lugar de fontes via CDN; Actuator expõe apenas `health`.
- Bloqueios / quem resolve: (1) criar remoto GitHub privado: Diego define nome e destino; (2) commits exigem assinatura GPG com a chave pessoal de Diego, que pede senha; Diego desbloqueia a chave; (3) WSL2 sem rede (VPN/DNS corporativo): Diego, se quiser paridade local.
- Próximo passo concreto: Diego valida a H01 e informa o remoto; então push de `main`, `develop` e `feature/H01-bootstrap` e PR para `develop`.

### H02 — Ambientes Docker e pipeline GitHub

- História e estado: H02, **bloqueada** (critérios de código/configuração atendidos; validações que exigem Docker, remoto e CI não executadas).
- Data/hora e agente: 08/10/2026, ~09:30–10:00 (BRT), Claude Code. Troca a partir da H01 autorizada pelo pedido de Diego (ver Situação real).
- Objetivo e critérios atendidos:
  - Compose dev (`infra/compose.dev.yml`) e HML (`infra/compose.hml.yml`) com PostgreSQL 18.6 só na rede interna e volume persistente; frontend na mesma origem com `/api` encaminhado ao backend.
  - Imagens backend (JDK/JRE 25, sem root) e frontend (Node 24.18.0 + nginx sem root), bases fixadas por digest.
  - `.env.example` com as variáveis do Compose, sem segredo; senha do banco obrigatória.
  - GitHub Actions: testa PR para `develop`/`main` (backend e frontend em contêineres fixados), relatórios como artefatos, smoke Compose, build das imagens e push no GHCR privado só em push para `develop`/`main`. Sem deploy automático.
  - `scripts/compose-smoke.sh` prova restart sem perder dados e banco sem porta publicada; `verify.sh` aceita `backend`/`frontend`.
- Critérios faltantes / motivo:
  - Build das imagens, restart sem perder DB e smoke: Docker Desktop 4.50.0 fecha ao iniciar (socket antigo em `%LOCALAPPDATA%\Docker\run`). Diego resolve.
  - Execução no WSL2: WSL com rede, mas sem JDK/Node instalados; não instalei nada sem pedido.
  - CI com artefatos, GHCR e “repositório usa develop/main”: dependem do push para o remoto e da primeira execução da CI.
- Branch e commit reais: `feature/H02-ambientes-docker-ci`, criada a partir de `feature/H01-bootstrap` (`403c606`); hash no `git log`.
- PR, checks e merge real: [dfdsn/fastPay#1](https://github.com/dfdsn/fastPay/pull/1) mergeado em `develop` (`216002c`) com o job de backend falhando (checksum do Maven Wrapper; ver Situação real). Frontend verde na CI. Correção no [PR #2](https://github.com/dfdsn/fastPay/pull/2), CI verde em `ebb6871`.
- Arquivos alterados: `infra/**` (Dockerfiles, `.dockerignore`, Compose dev/HML), `frontend/nginx/default.conf.template`, `.github/workflows/ci.yml`, `scripts/verify.sh`, `scripts/compose-smoke.sh`, `.env.example`, `README.md`, `AGENTS.md` (comandos), `docs/especificacao-tecnica.md`, `docs/decisoes-pendentes.md` (T-01), `docs/evidencias/H02-ambientes.md`, este arquivo. Sem migrations nem contratos de negócio.
- Testes:
  - `./scripts/verify.sh` (Git Bash, Windows 11 10.0.26200, Temurin 25.0.4.1+1, Node 24.18.0, npm 11.16.0), 08/10/2026 09:47–09:49 BRT: **exit 0** (6 testes backend, enforcer e JaCoCo ok; lint, 4 testes Vitest, build). Sem commit testado: árvore de trabalho.
  - `docker compose … config -q` dos dois arquivos: ok; sem `POSTGRES_PASSWORD` recusa com mensagem em português. Workflow lido como YAML válido.
- Testes não executados e por quê: `compose-smoke.sh` e build das imagens (Docker Desktop), WSL2 (sem JDK/Node), CI/GHCR (sem remoto), actionlint (não instalado).
- Decisões novas/alteradas: T-01 parcial atualizado. Escolhas técnicas rotineiras: PostgreSQL 18.6 (19 ainda beta); nginx sem root servindo o frontend e encaminhando `/api` em dev/HML; CI dentro de contêineres com o mesmo digest dos Dockerfiles; imagens etiquetadas `sha-<commit>` e nome da branch.
- Bloqueios / quem resolve: (1) chave GPG: resolvido em 08/10/2026; (2) Docker Desktop: Diego reinicia o Windows ou remove `%LOCALAPPDATA%\Docker\run` com o Docker fechado; (3) visibilidade privada dos pacotes no GHCR: Diego confere após a primeira publicação (T-02); (4) `JAVA_HOME` padrão aponta para o JDK 23: usar `%USERPROFILE%\.tools\jdk-25.0.4.1+1`.
- Próximo passo concreto: push, PR da H01 e da H02 para `develop`, conferir a CI (smoke Compose em Linux) e registrar o resultado.

### H03 — Migrations e fundação de isolamento RLS

- História e estado: H03, **aguardando revisão** (critérios atendidos e CI verde; sem merge).
- Data/hora e agente: 11/10/2026, Claude Code. Troca autorizada pelo pedido de Diego (ver Situação real).
- Objetivo e critérios atendidos no código: schema inicial pelo Flyway (V1 contexto, V2 `establishment` global e `audit_event` por unidade); papéis `fastpay_migration`/`fastpay_app`; contexto de tenant transacional (`TenantTransactions`); convenção de FK escopada com prova em fixture; tabela global separada com policy própria; Hibernate `validate`; RLS ENABLE + FORCE e fail-closed; Testcontainers com o mesmo script de papéis e a mesma imagem do Compose.
- Critérios faltantes / motivo: nenhum dos critérios da história. A execução local com PostgreSQL não é possível nesta máquina (Docker); a prova é a CI.
- Branch e commit reais: `feature/H03-migrations-rls` (a partir de `feature/H02-corrige-ci-maven`); hash no `git log`.
- PR, checks e merge real: [PR #3](https://github.com/dfdsn/fastPay/pull/3) para `develop`; run 38165808131 falhou por asserção (ver evidência §3.2); run 38165969878 em `0d10270` verde (backend, frontend, imagens e smoke). Sem merge.
- Arquivos/migrations/contratos: `backend/src/main/resources/db/migration/V1__contexto_de_tenant.sql`, `V2__estabelecimento_e_auditoria.sql`, `backend/pom.xml`, `application.yaml`, módulos `tenancy` e `establishments`, testes em `tenancy/` e `testsupport/`, fixture `db/testfixture/V1000__…`, `infra/postgres/init/01-roles.sh`, Compose, `compose-smoke.sh`, CI, `.env.example`, README, AGENTS, T-01, [evidência](evidencias/H03-migrations-rls.md).
- Testes: local `test-compile` ok e `Modularity*` 3/3 (Windows, Git Bash, Temurin 25.0.4.1+1, 11/10/2026). CI `0d10270`: 17/17 no backend com PostgreSQL 18.6 real via Testcontainers, smoke Compose com `ok 2 migrations; fastpay_app sem SUPERUSER/BYPASSRLS`.
- Decisões novas/alteradas: T-01 atualizado. Escolhas técnicas: contexto por `set_config(..., true)` e recusa de transação aninhada; `audit_event` como primeira tabela por unidade (append-only); `establishment` só legível no contexto até H10; senhas dos papéis por variável de ambiente no init.
- Bloqueios / quem resolve: Docker Desktop local (Diego); volumes de dev criados antes da H03 precisam ser recriados (`down -v`, dados sintéticos).
- Próximo passo concreto: revisão e merge do PR #2 e do PR #3 por Diego; depois, reconciliar o progresso com os hashes de merge.

### H04 — Eventos duráveis, idempotência e jobs

- História e estado: H04, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge).
- Data/hora e agente: 11/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - Intenção persistida na transação do fato: `Jobs.enqueue` exige transação ativa com contexto de unidade; commit publica, rollback descarta.
  - Deduplicação por efeito: um job por (unidade, tipo, chave); evento repetido devolve o job existente, inclusive depois de concluído.
  - Retry recuperável: reserva com lease por `claim_due_jobs` (`FOR UPDATE SKIP LOCKED`); worker caído é retomado quando o lease vence; job em execução fica travado e não roda duas vezes.
  - 5 tentativas totais para não financeiros, backoff progressivo configurável (`fastpay.jobs.*`), fila de falhas (`EXHAUSTED`) com último erro sanitizado, tentativas, próxima execução e correlationId.
  - Retomada autorizada da mesma operação (`Jobs.retryExhausted`) com auditoria (`job.retomada_autorizada`, ator e job em `audit_event.subject_id`).
  - Worker estabelece o contexto da unidade de cada job (RLS); a reserva global devolve só id, unidade e lease.
  - Financeiros sem teto de tentativas.
- Critérios faltantes / motivo: nenhum dos critérios da H04. Fica para histórias seguintes, por depender de identidade e permissões: o endpoint/tela da fila de falhas e a checagem de permissão do ator na retomada (H07/H11; hoje o chamador autoriza) e a chave de idempotência de comandos HTTP com hash do payload (especificação §4; entra com o primeiro comando crítico, H16). Nenhum efeito real usa a fila ainda (primeiro uso: e-mail em H12).
- Branch e commit reais: `feature/H04-eventos-duraveis-jobs` (a partir de `develop` `96ac6bf`); hash no `git log`.
- PR, checks e merge real: [PR #4](https://github.com/dfdsn/fastPay/pull/4) para `develop`; run 38170119581 em `f715888` verde (backend, frontend, imagens e smoke). Sem merge.
- Arquivos/migrations/contratos: `V3__fila_de_jobs.sql` (tabela `job`, função `claim_due_jobs`, coluna `audit_event.subject_id`), módulo `jobs` (`Jobs`, `JobHandler`, `JobRequest`, `JobExecution`, `JobFailure`, `JobSummary`, `domain/RetryPolicy`, `domain/SafeError`, `application/JobWorker`, `application/JobHandlers`, `infrastructure/*`), testes em `jobs/` e `MigrationsIntegrationTest`, `PostgresIntegrationTest` (worker desligado nos testes), `application.yaml`, especificação §9, decisões (T-01, T-06), [evidência](evidencias/H04-eventos-jobs.md).
- Testes: `./mvnw -B -ntp clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesmo digest da CI) com Docker para o Testcontainers (PostgreSQL 18.6, mesmo digest), 11/10/2026: **39 testes, 0 falhas**, enforcer e JaCoCo ok, BUILD SUCCESS. Detalhes e cobertura na evidência. CI do PR verde em `f715888` (run 38170119581).
- Testes não executados e por quê: frontend sem mudança (roda na CI); E2E/PIT fora do escopo (sem regra financeira crítica nesta história).
- Decisões novas/alteradas: T-06 (fila de jobs própria no PostgreSQL em vez do registry JDBC do Spring Modulith), registrada para aceite de Diego no PR.
- Bloqueios / quem resolve: nenhum para a H04. Aceite da T-06: Diego.
- Próximo passo concreto: Diego revisa o PR e decide a T-06; após o merge, reconciliar o progresso com o hash real.

## Registro de release (ainda nenhum)

Tag/imagens/digests; histórias incluídas; migrations; restore/backup; validação Diego; autorização deploy; ambiente/data; smoke; rollback compatível; resultado. Merge main não substitui autorização de publicação.

## Histórico documental

06/10/2026: consolidados PRD3.0, especificação, decisões, instruções dos agentes e54 histórias. Verificação desta entrega é documental (links/IDs/dependências/conflitos textuais), não teste funcional nem parecer externo. Persistem gates do PSP, políticas de identidade/privacidade e configurações produtivas.

07/10/2026: H01 implementada por Claude Code e repositório Git local iniciado (sem remoto e, como corrigido em 08/10/2026, sem commit até o desbloqueio do GPG). Detalhes no registro de execução da H01.

08/10/2026: H02 implementada por Claude Code; commits criados após desbloqueio do GPG; remoto informado por Diego. Detalhes no registro da H02.
