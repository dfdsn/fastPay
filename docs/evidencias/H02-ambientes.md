# H02 — Evidência de ambientes Docker e pipeline GitHub

Registro sanitizado. Claude Code, 08/10/2026, máquina de Diego (Windows 11 Home 10.0.26200, Git Bash). Sem segredos nem dados pessoais. As senhas do Compose de smoke são geradas na hora e descartadas.

## 1. O que foi entregue

| Arquivo | Conteúdo |
|---|---|
| `infra/backend.Dockerfile` (+ `.dockerignore`) | Build com Maven Wrapper no JDK 25, runtime JRE 25, usuário sem root, healthcheck em `/actuator/health/liveness` |
| `infra/frontend.Dockerfile` (+ `.dockerignore`) | Build Angular no Node 24.18.0/npm do lockfile; nginx sem root na porta 8080 |
| `frontend/nginx/default.conf.template` | Mesma origem: estáticos + `/api` encaminhado ao backend (sem buffer, pronto para SSE); `/healthz`; Actuator não publicado |
| `infra/compose.dev.yml` | Desenvolvimento: build local, PostgreSQL só na rede interna (`internal: true`, sem porta), volume persistente, frontend em `127.0.0.1:8088` |
| `infra/compose.hml.yml` | Homologação: mesmas peças com imagens do GHCR por tag de commit/release (`FASTPAY_VERSION`), sem build, porta 8089 |
| `.github/workflows/ci.yml` | PR e push em `develop`/`main`: backend e frontend em contêineres fixados por digest, relatórios como artefatos (14 dias), smoke Compose, build das imagens; push no GHCR só em push para `develop`/`main`. Sem deploy |
| `scripts/verify.sh` | Aceita `backend` ou `frontend` para os jobs da CI; sem argumento roda tudo (como em H01) |
| `scripts/compose-smoke.sh` | Sobe o Compose, confere `/healthz`, `/api` e a página pela mesma origem, confere que o banco não tem porta publicada, grava marcador, faz `down`/`up` e confere que o volume preservou o dado |
| `.env.example` | Variáveis do Compose (`POSTGRES_*`, `FASTPAY_HTTP_PORT`, `FASTPAY_IMAGE_OWNER`, `FASTPAY_VERSION`) com valores fictícios |

## 2. Versões fixadas (T-01, parte H02)

Digests de índice multi-arquitetura consultados no Docker Hub em 08/10/2026.

| Imagem | Tag | Digest |
|---|---|---|
| PostgreSQL | `postgres:18.6-trixie` | `sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` |
| JDK (build e CI) | `eclipse-temurin:25.0.4.1_1-jdk-noble` | `sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4` |
| JRE (runtime) | `eclipse-temurin:25.0.4.1_1-jre-noble` | `sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab` |
| Node (build e CI) | `node:24.18.0-trixie-slim` | `sha256:ae91dcc111a68c9d2d81ff2a17bda61be126426176fde6fe7d08ab13b7f50573` |
| nginx | `nginxinc/nginx-unprivileged:1.30.5-alpine3.24` | `sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3` |

PostgreSQL: escolhido o major 18 (último estável; 19 está em beta), patch 18.6. A compatibilidade com pgBackRest será provada em H51 e o Flyway/Testcontainers em H03. A partir do 18 a imagem oficial guarda dados em `/var/lib/postgresql/18/docker`; o volume monta `/var/lib/postgresql`.

Actions fixadas por SHA de commit (última release consultada na API do GitHub em 08/10/2026): `actions/checkout` v7.0.1, `actions/upload-artifact` v7.0.2, `docker/setup-buildx-action` v4.4.1, `docker/login-action` v4.6.0, `docker/metadata-action` v6.2.0, `docker/build-push-action` v7.4.0.

## 3. Execuções

### 3.1 Verificação completa com o `verify.sh` alterado

Comando (raiz, Git Bash): `./scripts/verify.sh`, com `JAVA_HOME` no Temurin 25.0.4.1+1, Node 24.18.0 e npm 11.16.0 (`NPM_CONFIG_PREFIX` em pasta vazia, ver H01 §3.2). Início 2026-10-08T09:47:05-03:00, fim 09:49:17. Resultado: **exit 0**.

```
Rule 0: RequireJavaVersion passed / Rule 1: RequireMavenVersion passed / Rule 2: DependencyConvergence passed
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS
All files pass linting.
 Test Files  2 passed (2)
      Tests  4 passed (4)
Branches     : 94.28% ( 33/35 )
Lines        : 100% ( 37/37 )
Application bundle generation complete.
== verificação concluída (all)
```

`./scripts/verify.sh xyz` termina com exit 2 e mensagem de uso. Saídas conferidas: `frontend/dist/frontend/browser/index.html` e `backend/target/fastpay-backend-0.1.0-SNAPSHOT.jar` existem nos caminhos que os Dockerfiles copiam.

Atenção: o `JAVA_HOME` padrão desta máquina aponta para o JDK 23. O enforcer recusa o build (exit 1) até apontar para o JDK 25 em `%USERPROFILE%\.tools\jdk-25.0.4.1+1`.

### 3.2 Validação estática

- `docker compose --env-file <tmp> -f infra/compose.dev.yml config -q`: ok. O mesmo para `compose.hml.yml` com `FASTPAY_IMAGE_OWNER`/`FASTPAY_VERSION` fictícios.
- Sem `POSTGRES_PASSWORD`, o Compose recusa: `required variable POSTGRES_PASSWORD is missing a value: defina POSTGRES_PASSWORD no .env`.
- Configuração resolvida: rede `fastpay-dev_internal` com `internal: true`; a única porta publicada é a do frontend (8088, em 127.0.0.1).
- `.github/workflows/ci.yml` lido como YAML válido (jobs `backend`, `frontend`, `images`). Não foi validado pelo GitHub nem pelo actionlint.

## 4. Não executado (bloqueios reais)

| Validação exigida | Situação | Motivo / quem resolve |
|---|---|---|
| Build das imagens e smoke Compose (`scripts/compose-smoke.sh`) | **Não executado** | O Docker Desktop 4.50.0 fecha ao iniciar. O log mostra `initializing Inference manager: listening on unix://…\AppData\Local\Docker\run\dockerInference: remove …: The file cannot be accessed by the system`. Há sockets antigos (24/09/2026) em `%LOCALAPPDATA%\Docker\run` que `del`/`File.Delete` não removem, e mover a pasta foi bloqueado como ação local destrutiva. Diego: reiniciar o Windows ou remover `%LOCALAPPDATA%\Docker\run` com o Docker fechado (ou "Reset to factory defaults", que apaga imagens/volumes locais) |
| Restart sem perder DB | **Não executado** | Coberto pelo `compose-smoke.sh`; depende do Docker acima |
| Execução no WSL2 | **Não executado** | O WSL Ubuntu voltou a ter rede (`curl https://repo1.maven.org/maven2/` → 200), mas não tem JDK nem Node instalados. Não foram instalados sem pedido. A CI roda em Linux e cobre a paridade quando existir remoto |
| CI com artefatos e publicação no GHCR | **Não executado** | Não existe repositório remoto (T-02, Diego). O workflow só roda depois do push |
