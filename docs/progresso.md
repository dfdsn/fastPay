# Progresso — fastPay

Atualizado em 08/10/2026. Responsável pelo aceite: Diego.

## Situação real

Atualizado em 08/10/2026 por Claude Code.

**Correção de registro (08/10/2026):** até 08/10/2026 o repositório local não tinha nenhum commit, embora esta seção dissesse que `main`, `develop` e `feature/H01-bootstrap` existiam. A chave GPG de Diego (assinatura obrigatória) estava bloqueada. Depois de desbloqueada, os commits foram criados: pacote documental em `main` (e `develop`), H01 em `feature/H01-bootstrap` e H02 em `feature/H02-ambientes-docker-ci`, criada a partir da H01 porque a H01 ainda não está em `develop`.

**Troca de história:** em 08/10/2026 Diego pediu “Implemente a próxima história elegível” com a H01 aguardando validação. Tratei o pedido como a autorização explícita de troca exigida pelo AGENTS. A H01 continua aguardando validação.

**Remoto:** Diego informou `https://github.com/dfdsn/fastPay.git` em 08/10/2026 e pediu que o trabalho passe a ser feito por lá. O resultado do push e da CI fica registrado na atualização seguinte deste arquivo.

História ativa: **H02, bloqueada** (código e configuração prontos; build de imagens e smoke Compose locais não executados por falha do Docker Desktop; CI ainda não executada). Próximo passo: push das branches, PRs para `develop` (H01 primeiro) e conferir a CI, que roda o smoke Compose em Linux.

## Estados

Não iniciada → em andamento → aguardando validação ou revisão → concluída. Bloqueada quando falta pré-requisito concreto. Aguardando revisão inclui PR aberto; concluída só após checks e merge develop verificados. Uma release possui registro separado do estado das histórias. Progresso inicialmente sem agente/branch/commit/PR.

## Histórias

| ID | História | Estado | Evidência / próximo passo |
|---|---|---|---|
| H01 | Bootstrap reproduzível e contratos de qualidade | Aguardando validação | Verificação local verde em 07/10/2026 ([evidência](evidencias/H01-build.md)); commit em `feature/H01-bootstrap`, sem PR; ver registro abaixo |
| H02 | Ambientes Docker e pipeline GitHub | Bloqueada | Arquivos prontos e `verify.sh` verde em 08/10/2026 ([evidência](evidencias/H02-ambientes.md)); Docker Desktop falhando localmente; CI pendente; ver registro abaixo |
| H03 | Migrations e fundação de isolamento RLS | Não iniciada | Sem evidência; ver dependências/gates no backlog |
| H04 | Eventos duráveis, idempotência e jobs | Não iniciada | Sem evidência; ver dependências/gates no backlog |
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
- PR, checks e merge real: nenhum até este commit.
- Arquivos alterados: `infra/**` (Dockerfiles, `.dockerignore`, Compose dev/HML), `frontend/nginx/default.conf.template`, `.github/workflows/ci.yml`, `scripts/verify.sh`, `scripts/compose-smoke.sh`, `.env.example`, `README.md`, `AGENTS.md` (comandos), `docs/especificacao-tecnica.md`, `docs/decisoes-pendentes.md` (T-01), `docs/evidencias/H02-ambientes.md`, este arquivo. Sem migrations nem contratos de negócio.
- Testes:
  - `./scripts/verify.sh` (Git Bash, Windows 11 10.0.26200, Temurin 25.0.4.1+1, Node 24.18.0, npm 11.16.0), 08/10/2026 09:47–09:49 BRT: **exit 0** (6 testes backend, enforcer e JaCoCo ok; lint, 4 testes Vitest, build). Sem commit testado: árvore de trabalho.
  - `docker compose … config -q` dos dois arquivos: ok; sem `POSTGRES_PASSWORD` recusa com mensagem em português. Workflow lido como YAML válido.
- Testes não executados e por quê: `compose-smoke.sh` e build das imagens (Docker Desktop), WSL2 (sem JDK/Node), CI/GHCR (sem remoto), actionlint (não instalado).
- Decisões novas/alteradas: T-01 parcial atualizado. Escolhas técnicas rotineiras: PostgreSQL 18.6 (19 ainda beta); nginx sem root servindo o frontend e encaminhando `/api` em dev/HML; CI dentro de contêineres com o mesmo digest dos Dockerfiles; imagens etiquetadas `sha-<commit>` e nome da branch.
- Bloqueios / quem resolve: (1) chave GPG: resolvido em 08/10/2026; (2) Docker Desktop: Diego reinicia o Windows ou remove `%LOCALAPPDATA%\Docker\run` com o Docker fechado; (3) visibilidade privada dos pacotes no GHCR: Diego confere após a primeira publicação (T-02); (4) `JAVA_HOME` padrão aponta para o JDK 23: usar `%USERPROFILE%\.tools\jdk-25.0.4.1+1`.
- Próximo passo concreto: push, PR da H01 e da H02 para `develop`, conferir a CI (smoke Compose em Linux) e registrar o resultado.

## Registro de release (ainda nenhum)

Tag/imagens/digests; histórias incluídas; migrations; restore/backup; validação Diego; autorização deploy; ambiente/data; smoke; rollback compatível; resultado. Merge main não substitui autorização de publicação.

## Histórico documental

06/10/2026: consolidados PRD3.0, especificação, decisões, instruções dos agentes e54 histórias. Verificação desta entrega é documental (links/IDs/dependências/conflitos textuais), não teste funcional nem parecer externo. Persistem gates do PSP, políticas de identidade/privacidade e configurações produtivas.

07/10/2026: H01 implementada por Claude Code e repositório Git local iniciado (sem remoto e, como corrigido em 08/10/2026, sem commit até o desbloqueio do GPG). Detalhes no registro de execução da H01.

08/10/2026: H02 implementada por Claude Code; commits criados após desbloqueio do GPG; remoto informado por Diego. Detalhes no registro da H02.
