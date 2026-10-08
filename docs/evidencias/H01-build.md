# H01 — Evidência de build, testes e versões

Registro sanitizado. Executado por Claude Code em 07/10/2026, na máquina de Diego (Windows 11, PowerShell/Git Bash). Sem dados pessoais, tokens ou segredos.

## 1. Versões fixadas (T-01, parte H01)

| Componente | Versão exata | Onde está fixada | Fonte / verificação |
|---|---|---|---|
| JDK | Eclipse Temurin 25.0.4.1+1 (LTS) | `maven-enforcer-plugin` exige `[25,26)`; README | API Adoptium; zip Windows x64 com SHA-256 `00c847d8…e9283` conferido |
| Maven | 3.9.16 | `.mvn/wrapper/maven-wrapper.properties` + `distributionSha256Sum`; enforcer exige `[3.9.16]` | SHA-512 oficial do Maven Central conferido; SHA-256 `5af3b743…d89ce` |
| Maven Wrapper | 3.3.4 (`only-script`, sem jar) | `maven-wrapper.properties` | Maven Central |
| Spring Boot | 4.1.1 | `spring-boot-starter-parent` | Maven Central (última 4.1.x estável) |
| Spring Framework | 7.0.9 | Gerenciado pelo Boot | BOM do Boot 4.1.1 |
| Spring Modulith | 2.1.1 | `spring-modulith-bom` importado explicitamente (o BOM do Boot 4.1.1 não o gerencia) | Maven Central |
| JUnit Jupiter | 6.0.3 | Gerenciado pelo Boot | BOM |
| Surefire | 3.5.6 | Gerenciado pelo Boot | BOM |
| JaCoCo | 0.8.15 | `pom.xml` | Maven Central |
| Maven Enforcer | 3.6.2 | `pom.xml` | Maven Central |
| Node.js | 24.18.0 | `frontend/.nvmrc`, `engines` | Instalação local já existente |
| npm | 11.16.0 (o que acompanha o Node 24.18.0) | `packageManager`, `engines` + `engine-strict` | Ver incompatibilidade 3.2 |
| Angular / CLI / build | 22.0.8 | `package.json` exato + `package-lock.json` | Último patch 22.0.x no registry npm |
| Angular Material / CDK | 22.0.7 | Idem | Último patch 22.0.x publicado |
| TypeScript | 6.0.3 | Idem | Resolvido por `~6.0.2` do CLI e congelado |
| RxJS | 7.8.2 | Idem | |
| Vitest / coverage-v8 | 4.1.11 | Idem | |
| ESLint / angular-eslint / typescript-eslint | 10.12.0 / 22.5.0 / 8.69.0 | Idem | angular-eslint 22.x declara compatibilidade com Angular 22 |

Todas as dependências npm estão com versão exata no `package.json` (`save-exact=true` em `.npmrc`) e a árvore completa no `package-lock.json`. Nenhuma tag `latest` ou intervalo flutuante.

Ainda abertos em T-01 (fora de H01): major/patch e digest do PostgreSQL (H02/H03), Testcontainers (versão 2.0.5 gerenciada pelo Boot, a validar em H03), Flyway 12.4.0 (H03), pgBackRest (H51), Playwright (história de E2E), PIT (primeira regra crítica; `pitest-maven` 1.30.0 é o release atual).

## 2. Execuções

### 2.1 Verificação completa a partir de estado limpo

Antes: removidos `backend/target`, `frontend/node_modules`, `frontend/dist`, `frontend/.angular`, `frontend/coverage`.

Comando (raiz, Git Bash): `./scripts/verify.sh`
Ambiente: Windows 11 Home 10.0.26200, Git Bash 2.47.1, `JAVA_HOME` = Temurin 25.0.4.1+1, Node 24.18.0, npm 11.16.0. Início 2026-10-07T13:44:08-03:00. Resultado: **exit 0**.

Trechos da saída:

```
== backend: mvnw clean verify
Rule 0: RequireJavaVersion passed / Rule 1: RequireMavenVersion passed / Rule 2: DependencyConvergence passed
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in tech.malyah.fastpay.ModularityGateTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in tech.malyah.fastpay.ModularityTest
Starting ApplicationInfoControllerTest using Java 25.0.4.1
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in tech.malyah.fastpay.platform.web.ApplicationInfoControllerTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS
== frontend: npm ci, lint, test:ci, build
added 602 packages in 9s
All files pass linting.
 Test Files  2 passed (2)
      Tests  4 passed (4)
Statements   : 100% ( 62/62 )
Branches     : 94.28% ( 33/35 )
Lines        : 100% ( 37/37 )
Application bundle generation complete.
== verificação concluída
```

Limitação: “estado limpo” significa sem artefatos do projeto; os caches `~/.m2` e do npm já estavam populados nesta máquina. Ainda não houve build em máquina/contêiner novo: a CI de H02 é que dará essa prova.

### 2.2 Prova de integração Boot 4.1.1 + Modulith 2.1.1 + Java 25

- `ApplicationInfoControllerTest` sobe o contexto Spring completo (`@SpringBootTest`) com `spring-modulith-starter-core` no classpath, em Java 25.0.4.1.
- `ModularityTest.modulosRespeitamFronteiras` executa `ApplicationModules.verify()` sobre a aplicação (módulo `platform`).
- `ModularityGateTest` prova que o gate não é vazio: um fixture com o módulo `tabs` importando `billing.internal` gera `Violations`. Sem essa prova, o verify passaria trivialmente por haver um único módulo.
- `ModularityTest.geraDocumentacaoDosModulos` gera PlantUML em `backend/target/spring-modulith-docs` (artefato de build, não versionado).

Ainda não coberto (H04): registry de eventos JDBC do Modulith e recuperação após restart.

### 2.3 Smoke manual da aplicação

Comandos: `java -jar backend/target/fastpay-backend-0.1.0-SNAPSHOT.jar` e, em `frontend/`, `npx ng serve --port 4200` (proxy `/api` → 8080).

| Verificação | Resultado |
|---|---|
| `GET http://localhost:8080/actuator/health` | 200 `{"groups":["liveness","readiness"],"status":"UP"}` |
| `GET http://localhost:4200/api/v1/platform/info` (via proxy) | 200 `{"name":"fastPay","version":"0.1.0-SNAPSHOT","environment":"development"}` |
| `GET http://localhost:8080/actuator/env` | 404 (só `health` exposto) |
| Tela em `http://localhost:4200` com backend no ar | Cabeçalho, título “Situação do sistema”, região `status` com “Servidor disponível.” e versão/ambiente |
| Mesma tela com backend parado | “Não foi possível contatar o servidor. Verifique sua conexão e tente novamente.”; Tab leva o foco ao botão “Tentar novamente” |

## 3. Incompatibilidades e desvios encontrados

1. **WSL2 sem rede nesta máquina.** No Ubuntu do WSL, `curl https://repo1.maven.org` dá timeout (DNS da VPN corporativa). A execução foi feita no Windows nativo, contrariando a recomendação de paridade WSL2 do AGENTS. O script é POSIX e roda em Linux; a paridade será provada na CI (H02) ou quando o WSL tiver rede.
2. **npm 11.0.0 global quebra a instalação** (`Cannot read properties of null (reading 'edgesOut')`). Há um npm 11.0.0 em `%APPDATA%\npm` que sobrepõe o npm 11.16.0 do Node. O projeto exige npm 11.16.0 via `engines` + `engine-strict`. Contorno usado sem alterar a configuração global: `NPM_CONFIG_PREFIX` apontando para pasta vazia na sessão, ou `node "C:\Program Files\nodejs\node_modules\npm\bin\npm-cli.js"`.
3. **npm 11.16 bloqueia scripts de instalação por padrão** (`esbuild`, `lmdb`, `msgpackr-extract`, `@parcel/watcher` listados em `allow-scripts`). Lint, testes e build funcionam sem eles (binários opcionais por plataforma). Nada foi aprovado; reavaliar se algum recurso precisar deles.
4. **Rede intermitente.** O DNS da VPN recusa alguns nomes e o `curl` do Git Bash não resolve `repo.maven.apache.org` (CNAME Cloudflare). O `mvnw` shell usou a distribuição Maven 3.9.16 já baixada e verificada pelo `mvnw.cmd`, copiada para o diretório de cache do script. É problema do ambiente, não do projeto.
5. **Mockito em Java 25** avisa sobre agente dinâmico; configurado como `-javaagent` no Surefire, preservando o agente do JaCoCo (`@{argLine}`).
6. **Defaults do Angular CLI 22 ajustados**: `strict` ausente no `tsconfig` gerado (ativado, mais `strictTemplates`); fontes do Google via CDN removidas (fonte do sistema); `lang="pt-BR"`; prefixo `fp`.

## 4. Gate de cobertura

JaCoCo `check` aplica 80% linhas / 70% branches aos pacotes `*.domain*` e `*.application*`. Em H01 não existem esses pacotes, então o gate passa sem medir nada; passará a valer na primeira história com regra de negócio. Cobertura do frontend é reportada (text-summary + lcov em `frontend/coverage/`), sem percentual mínimo, conforme a especificação.
