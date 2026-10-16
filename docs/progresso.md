# Progresso — fastPay

Atualizado em 08/10/2026. Responsável pelo aceite: Diego.

## Situação real

Atualizado em 16/10/2026 por Claude Code (H10 reconciliada; H11 em revisão).

**Correção de registro (08/10/2026):** até 08/10/2026 o repositório local não tinha nenhum commit, embora esta seção dissesse que `main`, `develop` e `feature/H01-bootstrap` existiam. A chave GPG de Diego (assinatura obrigatória) estava bloqueada. Depois de desbloqueada, os commits foram criados: pacote documental em `main` (e `develop`), H01 em `feature/H01-bootstrap` e H02 em `feature/H02-ambientes-docker-ci`, criada a partir da H01 porque a H01 ainda não está em `develop`.

**Troca de história:** em 08/10/2026 Diego pediu “Implemente a próxima história elegível” com a H01 aguardando validação. Tratei o pedido como a autorização explícita de troca exigida pelo AGENTS. A H01 continua aguardando validação.

**Remoto:** `https://github.com/dfdsn/fastPay` desde 08/10/2026. Branches `main`, `develop`, `feature/H01-bootstrap` e `feature/H02-ambientes-docker-ci` publicadas em 08/10/2026.

**PR #1 (11/10/2026, reconciliação):** [dfdsn/fastPay#1](https://github.com/dfdsn/fastPay/pull/1) (H01 + H02, base `develop`) foi mergeado por Diego; merge `216002c` em `develop`. A CI desse PR (run 37806871793) e a do push em `develop` (run 37828730825) **falharam** no job de backend: `Failed to validate Maven distribution SHA-256`. O frontend passou nas duas. Causa: sem `unzip` (caso da imagem Temurin e do WSL) o `mvnw` troca o `.zip` pelo `.tar.gz`, e o checksum fixado é o do `.zip` (conferido de novo contra o SHA-512 oficial em 11/10/2026). Reproduzido no WSL Ubuntu com clone limpo de `216002c`. O job de imagens e smoke Compose ficou `skipped` e nunca rodou. Correção na branch `feature/H02-corrige-ci-maven`.

**PR #2 (11/10/2026):** [dfdsn/fastPay#2](https://github.com/dfdsn/fastPay/pull/2) corrige a CI em dois pontos: `unzip` para o Maven Wrapper e healthcheck do backend sem `curl` (a imagem JRE não tem `curl`). CI verde no commit `ebb6871` (run 38105381291): backend, frontend, imagens e smoke Compose. Evidência em [evidencias/H02-ambientes.md](evidencias/H02-ambientes.md) §3.3–3.4.

Histórias aguardando revisão: **H01 e H02** (PR #2 aberto, CI verde). Próximo passo delas: Diego revisa e faz o merge do PR #2; depois, conferir a CI do push em `develop` e a publicação das imagens no GHCR (privadas) e registrar H01/H02 como concluídas com o hash real.

**Troca para H03 (11/10/2026):** Diego pediu “Siga com o desenvolvimento” com o PR #2 ainda aberto. Tratei como autorização para a próxima história. A H03 foi feita em `feature/H03-migrations-rls`, criada a partir de `feature/H02-corrige-ci-maven`, porque precisa da CI corrigida. **H03 aguardando revisão** no [PR #3](https://github.com/dfdsn/fastPay/pull/3): CI verde em `0d10270` (run 38165969878), com 17 testes, incluindo os de isolamento e migração em PostgreSQL real, e o smoke Compose. Próximo passo: Diego faz o merge do PR #2 e depois do PR #3; então conferir a CI em `develop` e registrar H01–H03 como concluídas com os hashes reais.

**Reconciliação após merge (11/10/2026):** Diego fez o merge do [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`, 19:05 UTC) e do [PR #3](https://github.com/dfdsn/fastPay/pull/3) (`96ac6bf`, 19:19 UTC) em `develop`. CI do push em `develop`: run 38166528466 em `33dfc80` e run 38167523089 em `96ac6bf`, ambas verdes (backend, frontend, imagens e smoke Compose). Com isso **H01, H02 e H03 estão concluídas**. A visibilidade privada dos pacotes no GHCR não pôde ser conferida pela API desta sessão (T-02, Diego confere).

**H04 (11/10/2026):** Diego pediu “Implemente a próxima história elegível”. Sem história em revisão, a menor elegível é a H04 (depende só da H03; H05 depende de acesso ao PSP). Feita em `feature/H04-eventos-duraveis-jobs`, a partir de `develop` (`96ac6bf`). **Aguardando revisão** no [PR #4](https://github.com/dfdsn/fastPay/pull/4), CI verde; detalhes no registro da H04.

**Reconciliação da H04 (11/10/2026):** Diego fez o merge do [PR #4](https://github.com/dfdsn/fastPay/pull/4) em `develop` (`4bed847`, 20:56 UTC). CI do push em `develop` verde (run 38174306620). **H04 concluída.** A T-06 (fila própria em vez do registry do Modulith) entrou em `develop` com o merge, mas não há aceite explícito registrado; continua aguardando a palavra de Diego.

**H07 (11/10/2026):** Diego pediu “pode seguir com a próxima história”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); a menor elegível é a H07 (depende só da H03). Feita em `feature/H07-identidade-vinculos`, a partir de `develop` (`4bed847`). **Aguardando revisão** no PR da H07; detalhes no registro da H07.

**Reconciliação da H07 (12/10/2026):** Diego fez o merge do [PR #5](https://github.com/dfdsn/fastPay/pull/5) em `develop` (`97dde12`, 11/10/2026 22:37 UTC). CI do push em `develop` verde (run 38181137432). **H07 concluída.**

**H08 (12/10/2026):** Diego pediu “próxima história”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); a menor elegível é a H08 (depende só da H07). O gate P-07 (política de senha) não tinha resposta: a H08 adotou uma proposta configurável, aprovada por Diego em 12/10/2026 junto com os limites de tentativa (P-07/T-03). Feita em `feature/H08-login-sessoes`, a partir de `develop` (`97dde12`). **Aguardando revisão** no PR da H08; detalhes no registro da H08.

**Reconciliação da H08 (13/10/2026):** Diego fez o merge do [PR #6](https://github.com/dfdsn/fastPay/pull/6) em `develop` (`85659f7`, 12/10/2026 03:17 UTC). CI do push em `develop` verde (run 38199173689). **H08 concluída.**

**H09 (13/10/2026):** Diego pediu “próxima historia”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); a menor elegível é a H09 (depende só da H08). Os gates P-07 (recuperação excepcional) e T-05 (bootstrap) não tinham resposta: a H09 implementou uma proposta configurável e a registrou como pendente de aprovação. Feita em `feature/H09-totp-bootstrap-admin`, a partir de `develop` (`85659f7`). **Aguardando revisão** no PR da H09; detalhes no registro da H09.

**Reconciliação da H09 (13/10/2026):** Diego fez o merge do [PR #7](https://github.com/dfdsn/fastPay/pull/7) em `develop` (`6a4bc0e`, 13/10/2026 13:51 UTC). CI do push em `develop` verde (run 38408452083). **H09 concluída.** O merge não é decisão: as propostas P-07 (recuperação excepcional de MFA e limite de códigos) e T-05 (bootstrap) continuam aguardando a palavra de Diego, assim como o aceite da T-06.

**H12 (13/10/2026):** Diego pediu “proxima historia”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); H10 depende de H09 e H12; a menor elegível é a H12 (depende de H04 e H08). Feita em `feature/H12-email-duravel`, a partir de `develop` (`6a4bc0e`). **Aguardando revisão** no PR da H12; detalhes no registro da H12.

**Reconciliação da H12 (14/10/2026):** Diego fez o merge do [PR #8](https://github.com/dfdsn/fastPay/pull/8) em `develop` (`21b2058`, 14/10/2026 00:54 UTC). CI do push em `develop` verde (run 38492760180). **H12 concluída** no código; o envio real pelo Gmail de homologação continua dependendo da conta de teste (T-02).

**H10 (14/10/2026):** Diego pediu “proxima Historia”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); com H09 e H12 em `develop`, a menor elegível é a H10. Feita em `feature/H10-estabelecimento-convite-dono`, a partir de `develop` (`21b2058`). **Aguardando revisão** no PR da H10; detalhes no registro da H10.

**Reconciliação da H10 (16/10/2026):** Diego fez o merge do [PR #9](https://github.com/dfdsn/fastPay/pull/9) em `develop` (`9151720`, 16/10/2026 01:52 UTC). CI do push em `develop` verde (run 38837453986). **H10 concluída.** T-02 (Gmail de homologação), P-07, T-05 e T-06 seguem aguardando Diego.

**H11 (16/10/2026):** Diego pediu “proxima historia”. H05 e H06 seguem bloqueadas pelo acesso ao PSP (P-01); com H10 em `develop`, a menor elegível é a H11. FR-66 não define quem é o suporte nem o prazo máximo: a H11 implementou uma proposta configurável, registrada como P-16, aguardando Diego. Feita em `feature/H11-equipe-permissoes-suporte`, a partir de `develop` (`9151720`). **Aguardando revisão** no PR da H11; detalhes no registro da H11.

## Estados

Não iniciada → em andamento → aguardando validação ou revisão → concluída. Bloqueada quando falta pré-requisito concreto. Aguardando revisão inclui PR aberto; concluída só após checks e merge develop verificados. Uma release possui registro separado do estado das histórias. Progresso inicialmente sem agente/branch/commit/PR.

## Histórias

| ID | História | Estado | Evidência / próximo passo |
|---|---|---|---|
| H01 | Bootstrap reproduzível e contratos de qualidade | Concluída | Merge em `develop` pelo [PR #1](https://github.com/dfdsn/fastPay/pull/1) (`216002c`) e correção da CI no [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`); CI de `develop` verde (run 38166528466) ([evidência](evidencias/H01-build.md)) |
| H02 | Ambientes Docker e pipeline GitHub | Concluída | [PR #1](https://github.com/dfdsn/fastPay/pull/1) + [PR #2](https://github.com/dfdsn/fastPay/pull/2) (`33dfc80`); CI de `develop` verde com imagens e smoke (run 38166528466) ([evidência](evidencias/H02-ambientes.md)); visibilidade do GHCR a conferir (T-02) |
| H03 | Migrations e fundação de isolamento RLS | Concluída | [PR #3](https://github.com/dfdsn/fastPay/pull/3) (`96ac6bf`); CI de `develop` verde (run 38167523089) ([evidência](evidencias/H03-migrations-rls.md)) |
| H04 | Eventos duráveis, idempotência e jobs | Concluída | [PR #4](https://github.com/dfdsn/fastPay/pull/4) (`4bed847`); CI de `develop` verde (run 38174306620) ([evidência](evidencias/H04-eventos-jobs.md)); aceite explícito da T-06 pendente |
| H05 | Prova PSP: recebedor, cobrança e split | Bloqueada | Falta acesso ao PSP (P-01); Diego providencia |
| H06 | Prova PSP: devolução, conciliação e mensalidade | Bloqueada | Depende da H05 e do acesso ao PSP (P-01) |
| H07 | Identidade, vínculo e seleção de unidade | Concluída | [PR #5](https://github.com/dfdsn/fastPay/pull/5) (`97dde12`); CI de `develop` verde (run 38181137432) ([evidência](evidencias/H07-identidade-vinculos.md)) |
| H08 | Login da equipe e sessões seguras | Concluída | [PR #6](https://github.com/dfdsn/fastPay/pull/6) (`85659f7`); CI de `develop` verde (run 38199173689) ([evidência](evidencias/H08-login-sessoes.md)); política de senha e limites aprovados (P-07/T-03) |
| H09 | TOTP, códigos e bootstrap administrativo | Concluída | [PR #7](https://github.com/dfdsn/fastPay/pull/7) (`6a4bc0e`); CI de `develop` verde (run 38408452083) ([evidência](evidencias/H09-mfa-bootstrap.md)); propostas P-07 (recuperação excepcional) e T-05 (bootstrap) ainda aguardam aprovação de Diego |
| H10 | Criar estabelecimento e convidar dono | Concluída | [PR #9](https://github.com/dfdsn/fastPay/pull/9) (`9151720`); CI de `develop` verde (run 38837453986) ([evidência](evidencias/H10-estabelecimento-convite-dono.md)); envio pelo Gmail de homologação depende da conta de teste (T-02) |
| H11 | Equipe, permissões e concessão de suporte | Aguardando revisão | PR da H11 para `develop` ([evidência](evidencias/H11-equipe-permissoes-suporte.md)); proposta P-16 (suporte) aguarda Diego; ver registro abaixo |
| H12 | Convites e e-mail durável em homologação | Concluída | [PR #8](https://github.com/dfdsn/fastPay/pull/8) (`21b2058`); CI de `develop` verde (run 38492760180) ([evidência](evidencias/H12-email-duravel.md)); envio pelo Gmail de homologação depende da conta de teste (T-02) |
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
- PR, checks e merge real: [PR #4](https://github.com/dfdsn/fastPay/pull/4) para `develop`; run 38170119581 em `f715888` verde (backend, frontend, imagens e smoke). Merge por Diego em `4bed847` (11/10/2026 20:56 UTC); CI de `develop` verde (run 38174306620).
- Arquivos/migrations/contratos: `V3__fila_de_jobs.sql` (tabela `job`, função `claim_due_jobs`, coluna `audit_event.subject_id`), módulo `jobs` (`Jobs`, `JobHandler`, `JobRequest`, `JobExecution`, `JobFailure`, `JobSummary`, `domain/RetryPolicy`, `domain/SafeError`, `application/JobWorker`, `application/JobHandlers`, `infrastructure/*`), testes em `jobs/` e `MigrationsIntegrationTest`, `PostgresIntegrationTest` (worker desligado nos testes), `application.yaml`, especificação §9, decisões (T-01, T-06), [evidência](evidencias/H04-eventos-jobs.md).
- Testes: `./mvnw -B -ntp clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesmo digest da CI) com Docker para o Testcontainers (PostgreSQL 18.6, mesmo digest), 11/10/2026: **39 testes, 0 falhas**, enforcer e JaCoCo ok, BUILD SUCCESS. Detalhes e cobertura na evidência. CI do PR verde em `f715888` (run 38170119581).
- Testes não executados e por quê: frontend sem mudança (roda na CI); E2E/PIT fora do escopo (sem regra financeira crítica nesta história).
- Decisões novas/alteradas: T-06 (fila de jobs própria no PostgreSQL em vez do registry JDBC do Spring Modulith), registrada para aceite de Diego no PR.
- Bloqueios / quem resolve: nenhum para a H04. Aceite da T-06: Diego.
- Próximo passo concreto: ~~Diego revisa o PR e decide a T-06~~ PR mergeado; falta só o aceite explícito da T-06.
- Estado após o merge: **concluída** (reconciliado em 11/10/2026 no registro da H07).

### H07 — Identidade, vínculo e seleção de unidade

- História e estado: H07, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge).
- Data/hora e agente: 11/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - Identidade global (`user_identity`) separada do vínculo por unidade (`membership`, com papel e situação).
  - Selecionar só unidade com vínculo ativo; lista mostra o papel em cada uma; com um único vínculo, entrada direta; unidade ativa e papel sempre visíveis no cabeçalho.
  - Trocar de unidade limpa o contexto no frontend (limpezas registradas) e no servidor (troca do id da sessão; cada operação revalida o vínculo na mesma transação).
  - Desativar vínculo preserva a linha (autor, data, papel), audita e não afeta vínculos da pessoa em outras unidades; a sessão com a unidade revogada perde acesso na operação seguinte.
  - Acesso global (admin fastPay) não concede operação de loja.
  - Admin de uma loja não vê vínculos em outras (RLS).
- Critérios faltantes / motivo: nenhum dos critérios da H07. Fica para as histórias seguintes: login que grava a identidade na sessão, Spring Session JDBC e CSRF (H08; até lá a API responde 401 fora dos testes); criar vínculos por convite e o comando HTTP de desativação com permissão (H10/H11); permissões por ação (H11).
- Branch e commit reais: `feature/H07-identidade-vinculos` (a partir de `develop` `4bed847`); código em `c5de5a5`, documentação no commit seguinte.
- PR, checks e merge real: PR da H07 para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V4__identidade_e_vinculos.sql` (`current_identity_id()`, `user_identity`, `membership`, policies e leitura do nome das próprias unidades), módulo `identity` (`Memberships`, `SessionContext`, `UnitMembership`, `MembershipRole`, `IdentitySummary`, exceções, `infrastructure/JdbcMembershipStore`, `web/SessionUnitController`, `web/IdentityProblems`), `tenancy.TenantTransactions.asIdentity`, frontend `src/app/session/*`, `app.ts`, `app.routes.ts`, `app.config.ts`, especificação §5, [evidência](evidencias/H07-identidade-vinculos.md).
- Testes: backend `./mvnw -B -ntp clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesmo digest da CI) com Docker para o Testcontainers (PostgreSQL 18.6), em `c5de5a5`: **61 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Frontend `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim` (mesmo digest da CI): lint ok, **28 testes Vitest**, build ok. Detalhes na evidência.
- Testes não executados e por quê: E2E Playwright (ainda não configurado no projeto); jornada com login real (depende de H08).
- Decisões novas/alteradas: nenhuma de produto. Escolhas técnicas registradas na especificação §5: unidade escolhida na sessão do servidor, 409 `UNIDADE_NAO_SELECIONADA`, mesma resposta 403 para unidade alheia/inexistente/desativada.
- Bloqueios / quem resolve: nenhum para a H07.
- Próximo passo concreto: Diego revisa e faz o merge do PR da H07; depois, reconciliar o progresso com o hash real. A próxima elegível é a H08 (gate P-07, política de senha, antes de concluir).
- Estado após o merge: **concluída** ([PR #5](https://github.com/dfdsn/fastPay/pull/5), `97dde12`, CI de `develop` run 38181137432; reconciliado em 12/10/2026 no registro da H08).

### H08 — Login da equipe e sessões seguras

- História e estado: H08, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge). Política de senha e limites de tentativa aprovados por Diego em 12/10/2026 (P-07/T-03).
- Data/hora e agente: 12/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - E-mail/senha da equipe com hash `{bcrypt}`; Spring Session JDBC (schema na V5) e cookie `FASTPAY_SESSION` HttpOnly, Secure e SameSite=Lax; login descarta a sessão anterior e emite id novo.
  - CSRF em toda mutação (inclusive login, logout e redefinição), integrado ao Angular.
  - Expiração por perfil (inatividade e absoluta, perfil mais restritivo alcançável), aplicada no servidor antes de qualquer operação; polling marcado com `X-Fastpay-Background` não renova; trocar de unidade não estende o início.
  - Redefinição: link de 30 min, uso único, pedido novo invalida o anterior, concluir revoga todas as sessões; MFA não é tocado.
  - Mensagens não enumeram contas (login e pedido de redefinição com a mesma resposta; bloqueio por tentativas também para e-mail inexistente).
  - Remetente de teste (caixa em memória) até H12; nenhum e-mail real.
  - Frontend: telas de login, “esqueci minha senha” e nova senha; guard de sessão; volta ao login com sessão vencida; nome e “Sair” no cabeçalho.
- Critérios faltantes / motivo: nenhum dos critérios de código da H08. Política de senha (P-07) e limites (T-03) aprovados por Diego em 12/10/2026. Fora do aceite e registrados na evidência: aviso antes de expirar (a API já devolve os prazos), MFA (H09), e-mail real (H12), E2E (Playwright não configurado).
- Branch e commit reais: `feature/H08-login-sessoes` (a partir de `develop` `97dde12`); hash no `git log`.
- PR, checks e merge real: [PR #6](https://github.com/dfdsn/fastPay/pull/6) para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V5__credenciais_e_sessoes.sql` (`user_credential`, `password_reset_token`, `auth_attempt`, `spring_session*`, policy de login em `user_identity`), `backend/pom.xml` (Spring Security, Spring Session JDBC, security-test), `application.yaml` (`fastpay.auth.*`), `tenancy.TenantTransactions` (`forLoginEmail`, `forResetToken`), módulo `identity` (`SessionContext`, `domain/*`, `application/*`, `infrastructure/AuthConfiguration`, `AuthProperties`, `JdbcCredentialStore`, `JdbcAuthAttemptStore`, `TestMailbox`, `web/AuthController`, `SessionExpiryFilter`, `TestMailboxController`), frontend `src/app/auth/*`, `app.ts`, `app.routes.ts`, `app.config.ts`, `status-page.ts`, Compose dev/HML, `.env.example`, README, especificação §5, decisões P-07/T-03, [evidência](evidencias/H08-login-sessoes.md).
- Testes: backend `./mvnw -B -ntp clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesmo digest da CI) com Docker para o Testcontainers (PostgreSQL 18.6): **109 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Frontend `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim` (mesmo digest da CI): lint ok, **53 testes Vitest**, build ok. Smoke com o jar real e `curl` (CSRF, reset, login, sessão, logout). Detalhes na evidência.
- Testes não executados e por quê: E2E Playwright (não configurado); envio real de e-mail (H12); reset com MFA real (H09).
- Decisões novas/alteradas: P-07 (política de senha) e T-03 (limites de login e redefinição), propostos na H08 e aprovados por Diego em 12/10/2026. Escolhas técnicas na especificação §5: bcrypt prefixado, token no fragmento do link, header `X-Fastpay-Background`, cookie definido por customizador (o Boot 4.1 ignora `server.servlet.session.cookie.*` sem servidor embutido).
- Bloqueios / quem resolve: nenhum para a H08. Aceite da T-06 continua pendente desde a H04.
- Próximo passo concreto: Diego revisa e faz o merge do PR #6; depois do merge, reconciliar o progresso com o hash real. A próxima elegível depois da H08 é a H09 (TOTP, códigos e bootstrap; gate P-07 recuperação excepcional e T-05).

### H09 — TOTP, códigos e bootstrap administrativo

- História e estado: H09, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge). Recuperação excepcional de MFA (P-07) e bootstrap do admin (T-05) implementados como proposta, aguardando aprovação de Diego.
- Data/hora e agente: 13/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - MFA privilegiado obrigatório (admin fastPay, admin do estabelecimento, gerente) antes de qualquer função; opcional para garçom e produção, ativável pela tela “Segurança”.
  - TOTP (RFC 6238) com janela de um passo de cada lado e sem reuso do mesmo passo; segredo cifrado com chave fora do banco (`MFA_ENCRYPTION_KEY`).
  - 10 códigos de recuperação de uso único, só hash no banco, mostrados uma vez.
  - Senha + código de recuperação abre só o recadastro, que invalida autenticador e códigos antigos, encerra todas as sessões e envia aviso (caixa de teste até H12).
  - Promoção durante a sessão exige o cadastro antes da operação seguinte; redefinir a senha não remove MFA.
  - Bootstrap do primeiro admin fastPay por comando de operador, sem cadastro público nem credencial padrão; recuperação excepcional (inclusive do único admin) pelo mesmo caminho, com operador e motivo na trilha `security_event`. Procedimento no README e na especificação §5.
  - Frontend: telas `/verificacao` e `/configurar-verificacao`; login, guard e interceptor levam à etapa certa.
- Critérios faltantes / motivo: aprovação de Diego para a recuperação excepcional (P-07) e para o bootstrap (T-05); ensaio no ambiente produtivo fica para H50. Fora do aceite: QR code na tela (exigiria biblioteca nova; há link `otpauth://` e chave para digitar), aviso por e-mail real (H12), E2E (Playwright não configurado).
- Branch e commit reais: `feature/H09-totp-bootstrap-admin` (a partir de `develop` `85659f7`); hash no `git log`.
- PR, checks e merge real: PR da H09 para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V6__mfa_e_operador.sql`; módulo `identity` (`MfaRequiredException`, `SessionContext`, `domain/AuthStage`, `Totp`, `Base32`, `RecoveryCodes`, `application/MultiFactor`, `OperatorCommands`, `AccountMailer` (antes `PasswordResetMailer`), `TeamAuthentication`, `infrastructure/MfaSecretCipher`, `JdbcMfaStore`, `OperatorCommandRunner`, `AuthProperties`, `TestMailbox`, `web/MfaController`, `AuthController`, `IdentityProblems`, `SessionExpiryFilter`); `application.yaml`; frontend `src/app/auth/*`, `app.ts`, `app.routes.ts`; Compose dev/HML, `scripts/compose-smoke.sh`, `.env.example`, README, especificação §5, decisões P-07/T-05, [evidência](evidencias/H09-mfa-bootstrap.md).
- Testes: backend `./mvnw -B -ntp verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` com Docker para o Testcontainers (PostgreSQL 18.6): **155 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Frontend `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim`: lint ok, **68 testes Vitest**, build ok. Smoke com o jar real (bootstrap, cadastro do MFA pela API, recuperação excepcional). Detalhes na evidência.
- Testes não executados e por quê: `compose-smoke.sh` (roda na CI do PR); E2E Playwright (não configurado); aplicativo autenticador num celular real; envio real de e-mail (H12).
- Decisões novas/alteradas: propostas P-07 (recuperação excepcional e limite de 5 códigos errados por identidade em 15 min) e T-05 (bootstrap), aguardando Diego. Escolhas técnicas na especificação §5: etapas de sessão, AES-256-GCM com id da identidade como dado associado, códigos de 16 caracteres, comandos de operador com a credencial de migração.
- Bloqueios / quem resolve: aprovação de P-07/T-05 (Diego). Aceite da T-06 continua pendente desde a H04.
- Próximo passo concreto: Diego revisa o PR, decide P-07/T-05 e faz o merge; depois, reconciliar o progresso com o hash real. A próxima elegível depois da H09 é a H12 (convites e e-mail durável; H10 depende de H09 e H12).
- Estado após o merge: **concluída** ([PR #7](https://github.com/dfdsn/fastPay/pull/7), `6a4bc0e`, CI de `develop` run 38408452083; reconciliado em 13/10/2026 no registro da H12). P-07/T-05 continuam pendentes de aprovação.

### H12 — Convites e e-mail durável em homologação

- História e estado: H12, **aguardando revisão** (critérios de código atendidos e testados com PostgreSQL real e servidor SMTP de teste; sem merge). Envio real pelo Gmail de homologação depende da conta de teste (T-02).
- Data/hora e agente: 13/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - EmailPort com SMTP (STARTTLS obrigatório, prazos curtos) e caixa de teste de desenvolvimento; módulo `mail` novo.
  - Modelos de convite, redefinição de senha e aviso de MFA trocado, em português, com o ambiente marcado fora de produção.
  - E-mail durável pela fila de jobs (H04), agora também por identidade (V7): redefinição de senha e aviso de MFA gravados na transação do fato; 5 tentativas; falha visível na fila de falhas com erro sanitizado, no log e na métrica `fastpay.email`.
  - Links com `PUBLIC_BASE_URL` (substitui `PASSWORD_RESET_LINK_BASE`); token no fragmento; nenhum token em claro na fila: o link nasce no envio e o prazo conta dele.
  - Destinatários de teste protegidos: lista obrigatória com SMTP fora de produção; quem está fora não recebe nem ganha link.
  - Configuração perigosa não sobe (segredo SMTP ausente, caixa de teste em produção, caixa legível fora de dev).
  - Reenvio: a redefinição é reenviada por um pedido novo pela mesma tela e limites (mesma autorização da origem); repetição de um envio usa o mesmo `Message-ID` e deixa só o último link valendo.
- Critérios faltantes / motivo: envio real pelo Gmail de homologação (conta e senha de app ainda não existem, T-02). Fluxo do convite em si (token de 48 h, aceite, reenvio que invalida o anterior e quem pode reenviar) entra com a H10/H11, que criam o convite e usam o modelo, o link e o envio desta história. Tela da fila de falhas e retomada de jobs de conta com H11. Status de entrega (bounce) com o SES (H49).
- Branch e commit reais: `feature/H12-email-duravel` (a partir de `develop` `6a4bc0e`); hash no `git log`.
- PR, checks e merge real: PR da H12 para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V7__jobs_por_identidade.sql`; módulo `jobs` (`Jobs`, `JobExecution` com `identityId`, `JobWorker`, `JdbcJobStore`); módulo novo `mail` (`Emails`, `EmailTemplates`, `PublicLinks`, `EmailMessage`, `application/EmailPort`, `domain/RecipientAllowlist`, `infrastructure/MailProperties`, `MailConfiguration`, `SmtpEmailPort`, `TestMailbox`, `web/TestMailboxController`); módulo `identity` (`PasswordResets`, `MultiFactor`, `PasswordResetEmailJob`, `MfaReplacedEmailJob`, `OperatorCommandRunner`, `AuthProperties`; removidos `AccountMailer`, `TestMailbox` e `TestMailboxController` da identidade); `backend/pom.xml` (`spring-boot-starter-mail`); `application.yaml`; Compose dev/HML; `.env.example`; README; especificação §5/§9; decisões D-25/T-02; [evidência](evidencias/H12-email-duravel.md).
- Testes: backend `./mvnw -B -ntp verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` com Docker para o Testcontainers (PostgreSQL 18.6 e Mailpit): **179 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Detalhes na evidência.
- Testes não executados e por quê: frontend sem mudança (roda na CI); `compose-smoke.sh` (roda na CI do PR); envio pelo Gmail real (T-02); E2E Playwright (não configurado).
- Decisões novas/alteradas: nenhuma de produto. Escolhas técnicas registradas na especificação §9: jobs por identidade, token criado no envio, lista de destinatários por endereço ou domínio, destinatário bloqueado não é falha a repetir.
- Bloqueios / quem resolve: conta Gmail de homologação com senha de app e lista de destinatários de teste (T-02, Diego). P-07, T-05 e T-06 seguem aguardando Diego.
- Próximo passo concreto: Diego revisa e faz o merge do PR da H12; depois, reconciliar com o hash real. Com H09 e H12 em `develop`, a próxima elegível é a H10 (criar estabelecimento e convidar dono; gate T-05).

### H10 — Criar estabelecimento e convidar dono

- História e estado: H10, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge).
- Data/hora e agente: 14/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - Admin fastPay cadastra nome, e-mail do dono e CNPJ quando informado; ID interno é um UUID gerado pelo servidor; unidade nasce em `CONFIGURACAO`. Só o admin fastPay (papel conferido no banco a cada chamada; sessão com MFA).
  - Convite do dono (papel administrador do estabelecimento) gravado na mesma transação, com e-mail pela fila (H12); 48 h a partir do envio, uso único; token só no envio e só como hash.
  - Aceite: sem conta, a pessoa cria nome e senha no próprio aceite; com conta, entra (com MFA quando exigido) e aceita com o e-mail do convite; aceites simultâneos têm um vencedor.
  - Reenvio pelo admin fastPay invalida o convite anterior; recusado depois do aceite.
  - Cadastro não ativa nem gera mensalidade: nenhum job além do e-mail do convite; nenhuma tabela de cobrança tocada.
  - Validações exigidas: duplicidade de aceite, token vencido, tentativa por loja (admin de estabelecimento) e por garçom, ausência de CNPJ permitida e nenhum fluxo produtivo liberado. Consumidor ainda não tem sessão (H15/H27): a tentativa sem sessão de equipe cai em 401.
  - Telas: área do admin fastPay (`/admin/estabelecimentos`, com atalho na escolha de unidade) e aceite de convite (`/convite`).
- Critérios faltantes / motivo: nenhum do texto da H10. Fora do escopo: o dono convidar a equipe (H11), ativação e ciclo comercial (H39), validação cadastral do CNPJ/titular (P-02), envio real pelo Gmail de homologação (T-02).
- Branch e commit reais: `feature/H10-estabelecimento-convite-dono` (a partir de `develop` `21b2058`); hash no `git log`.
- PR, checks e merge real: PR da H10 para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V8__estabelecimento_e_convites.sql`; módulo `establishments` (`application/EstablishmentRegistry`, `domain/Cnpj`, `infrastructure/JdbcEstablishmentStore`, `web/EstablishmentController`); módulo `identity` (`PlatformAccess`, `PlatformAccessDeniedException`, `Invitations`, `MembershipRole.label`, `application/InvitationAcceptance`, `application/InvitationEmailJob`, `infrastructure/JdbcInvitationStore`, `web/InvitationController`, `SessionUnitController`, `IdentityProblems`); `tenancy.TenantTransactions` (contextos combinados); frontend (`invitations/`, `platform-admin/`, rotas, `AuthService.withCsrf`, seleção de unidade); especificação §5; decisões D-19/P-02/T-05; [evidência](evidencias/H10-estabelecimento-convite-dono.md).
- Testes: backend `./mvnw clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` com Docker para o Testcontainers: **203 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Frontend `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim`: lint ok, **83 testes**, build ok. Detalhes na evidência.
- Testes não executados e por quê: envio real pelo Gmail de homologação (T-02); E2E Playwright (não configurado); `compose-smoke.sh` (roda na CI do PR).
- Decisões novas/alteradas: nenhuma de produto. Escolhas técnicas registradas na especificação §5: UUID como ID interno; CNPJ numérico e alfanumérico só com verificadores, sem unicidade; conta existente precisa entrar para aceitar e o e-mail da conta tem de ser o do convite; erros de token limitados como os links de redefinição.
- Bloqueios / quem resolve: T-02 (conta Gmail de homologação, Diego). P-07 (recuperação excepcional e limite de códigos MFA), T-05 (bootstrap) e T-06 seguem aguardando a palavra de Diego. P-02 segue parcial (não bloqueia a H10, só a ativação).
- Próximo passo concreto: Diego revisa e faz o merge do PR da H10; depois, reconciliar com o hash real. A próxima elegível passa a ser a H11 (equipe, permissões e concessão de suporte).

- Estado após o merge: **concluída** ([PR #9](https://github.com/dfdsn/fastPay/pull/9), `9151720`, CI de `develop` run 38837453986; reconciliado em 16/10/2026 no registro da H11).

### H11 — Equipe, permissões e concessão de suporte

- História e estado: H11, **aguardando revisão** (critérios atendidos e testados com PostgreSQL real; sem merge). Proposta P-16 aguarda Diego.
- Data/hora e agente: 16/10/2026, Claude Code (sessão em nuvem, a partir do GitHub).
- Objetivo e critérios atendidos:
  - Permissões por ação (PRD §4.2), conferidas no servidor em cada comando; matriz testada por ação, inclusive devolução, débito, regularização e invalidação externa não delegáveis.
  - Dono (admin do estabelecimento) convida papéis, reenvia e cancela convite, troca papel e desativa; gerente vê a equipe e delega a garçons só ações delegáveis.
  - Último admin preservado, inclusive com duas remoções ao mesmo tempo; ninguém altera o próprio vínculo.
  - Desativação, delegação e revogação valem na sessão ativa; promoção exige MFA na próxima requisição.
  - Suporte: o dono concede a uma pessoa da equipe fastPay acesso temporário de leitura, com motivo e prazo, e encerra quando quiser; cada consulta é auditada com a identidade do suporte, roda somente leitura no banco e não assume a identidade de ninguém da loja. Vencida, encerrada, de outra loja ou sem concessão: recusa.
  - Telas: equipe (`/equipe`), acesso de suporte do dono (`/acesso-de-suporte`) e área de suporte (`/admin/suporte`).
- Critérios faltantes / motivo: nenhum do texto da H11. Fora do escopo: consultas do suporte a comandas, produção e pagamentos (entram com as histórias desses dados, pelo mesmo mecanismo); tela da fila de falhas (citada na H12, fica para quando houver operação de loja a retomar).
- Branch e commit reais: `feature/H11-equipe-permissoes-suporte` (a partir de `develop` `9151720`); hash no `git log`.
- PR, checks e merge real: PR da H11 para `develop`; CI a conferir no PR. Sem merge.
- Arquivos/migrations/contratos: `V9__equipe_permissoes_suporte.sql`; módulo `identity` (`Permission`, `PermissionDeniedException`, `Team`, `UnitMembership.permissions`, `Memberships.inUnit` com permissão, `domain/AccessPolicy`, `infrastructure/JdbcMembershipStore`, `JdbcInvitationStore`, `web/TeamController`, `SessionUnitController`, `IdentityProblems`); módulo `platform` (`SupportAccess`, `SupportNotAuthorizedException`, `infrastructure/JdbcSupportGrantStore`, `SupportProperties`, `SupportConfiguration`, `web/SupportController`); `tenancy.TenantTransactions.readOnlyInEstablishmentAs`; `application.yaml` (`fastpay.support.max-duration`); frontend (`team/`, `support/`, rotas, início da unidade, escolha de unidade); especificação §5; decisão P-16; [evidência](evidencias/H11-equipe-permissoes-suporte.md).
- Testes: backend `./mvnw clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` com Docker para o Testcontainers: **243 testes, 0 falhas**, enforcer, Modulith e JaCoCo ok. Frontend `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim`: lint ok, **102 testes**, build ok. Detalhes na evidência.
- Testes não executados e por quê: E2E Playwright (não configurado); `compose-smoke.sh` (roda na CI do PR); envio real de convite pelo Gmail de homologação (T-02).
- Decisões novas/alteradas: P-16 (proposta, aguarda Diego): suporte = admin fastPay escolhido pelo e-mail, prazo de 1 h a 72 h configurável, consulta só leitura auditada. Escolhas técnicas na especificação §5: delegação só a garçom; admin do estabelecimento exerce as permissões do gerente; trocar papel de garçom revoga as delegações; perfil de inatividade só muda no próximo login.
- Bloqueios / quem resolve: P-16 (Diego). T-02, P-07, T-05 e T-06 seguem aguardando Diego.
- Próximo passo concreto: Diego revisa e faz o merge do PR da H11 e responde a P-16; depois, reconciliar com o hash real.

## Registro de release (ainda nenhum)

Tag/imagens/digests; histórias incluídas; migrations; restore/backup; validação Diego; autorização deploy; ambiente/data; smoke; rollback compatível; resultado. Merge main não substitui autorização de publicação.

## Histórico documental

06/10/2026: consolidados PRD3.0, especificação, decisões, instruções dos agentes e54 histórias. Verificação desta entrega é documental (links/IDs/dependências/conflitos textuais), não teste funcional nem parecer externo. Persistem gates do PSP, políticas de identidade/privacidade e configurações produtivas.

07/10/2026: H01 implementada por Claude Code e repositório Git local iniciado (sem remoto e, como corrigido em 08/10/2026, sem commit até o desbloqueio do GPG). Detalhes no registro de execução da H01.

08/10/2026: H02 implementada por Claude Code; commits criados após desbloqueio do GPG; remoto informado por Diego. Detalhes no registro da H02.
