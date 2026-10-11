# H03 — Evidência de migrations e fundação de isolamento RLS

Registro sanitizado. Claude Code, 11/10/2026, máquina de Diego (Windows 11 Home 10.0.26200, Git Bash) e GitHub Actions. Sem segredos: as senhas dos testes e do smoke são geradas a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Papéis | `infra/postgres/init/01-roles.sh` | `fastpay_migration` (dono do schema `fastpay`, usado só pelo Flyway) e `fastpay_app` (runtime: sem SUPERUSER, sem BYPASSRLS, NOINHERIT, não é dono de tabela). `public` sem acesso. Mesmo script no Compose e no Testcontainers |
| Contexto de tenant | `V1__contexto_de_tenant.sql`, `tenancy/TenantTransactions.java` | `current_establishment_id()` lê `app.establishment_id`; sem valor retorna NULL e as policies negam tudo. A aplicação grava o valor com `set_config(..., true)`, que vale só para a transação (commit e rollback limpam). Chamar dentro de outra transação é recusado |
| Tabela global | `V2__…`: `establishment` | RLS ENABLE + FORCE; o runtime só lê a unidade do contexto; sem INSERT/UPDATE para o runtime até H10 definir o fluxo administrativo |
| Tabela por estabelecimento | `V2__…`: `audit_event` | `establishment_id NOT NULL`, RLS ENABLE + FORCE, leitura e inclusão só na unidade do contexto; sem UPDATE/DELETE (append-only) |
| FK escopada | convenção no cabeçalho do `V2__…`; fixture de teste `db/testfixture/V1000__fixture_fk_escopada.sql` | Pai com `UNIQUE (establishment_id, id)` e filho com FK composta. As tabelas reais com relação pai/filho chegam nas próximas histórias e seguem a convenção |
| Hibernate | `ddl-auto: validate`, `open-in-view: false`; entidade `establishments.infrastructure.EstablishmentEntity` | Validação do mapeamento contra o schema do Flyway na subida |
| Credenciais | `application.yaml`, `.env.example`, Compose | Runtime `JDBC_APP_PASSWORD`, Flyway `JDBC_MIGRATION_PASSWORD`; `FLYWAY_ENABLED=false` permite tirar a migração do processo web em produção (H50) |
| CI | `.github/workflows/ci.yml` | Job de backend monta o socket do Docker do runner e usa `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` |
| Compose | `compose.dev.yml`, `compose.hml.yml`, `compose-smoke.sh` | Init dos papéis, healthcheck do banco por TCP (o servidor temporário do init só usa socket local) e smoke conferindo migrations por `fastpay_migration` e runtime sem privilégios |

## 2. Versões (T-01, parte H03)

Gerenciadas pelo BOM do Spring Boot 4.1.1, sem sobrescrita: Flyway 12.4.0 (`spring-boot-starter-flyway` + `flyway-database-postgresql`), Hibernate 7.4.5.Final, driver PostgreSQL 42.7.13, Testcontainers 2.0.5 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) e `spring-boot-testcontainers`. Imagem dos testes: a mesma `postgres:18.6-trixie@sha256:74935e72…dcfc4336` do Compose.

## 3. Testes

| Teste | Critério da H03 |
|---|---|
| `MigrationsIntegrationTest.bancoVazioRecebeTodasAsMigrationsEFicaValido` | Migration em banco vazio; `validate` e reexecução sem efeito |
| `MigrationsIntegrationTest.bancoNaVersaoAnteriorAtualizaSemPerderDados` | Atualização V1 → V2 |
| `TenantIsolationIntegrationTest.runtimeNaoEhSuperusuarioNemIgnoraRlsNemEhDonoDasTabelas` | Usuário sem BYPASSRLS; todas as tabelas com RLS forçado; runtime não é dono |
| `…semContextoNadaEhVisivelNemGravavel` | Tenant ausente negado (leitura e escrita) |
| `…unidadeSoEnxergaEGravaOsPropriosDados`, `…gravarComIdDeOutraUnidadeEhRecusado` | Acesso cruzado negado (CA-03) |
| `…conexaoReaproveitadaAposRollbackOuCommitNaoCarregaUnidadeAnterior` | Pool com uma conexão após rollback e após commit |
| `…runtimeNaoConsegueDesligarRls`, `…auditoriaNaoPodeSerAlteradaNemApagadaPeloRuntime` | `row_security = off` falha; append-only |
| `…trocarDeUnidadeDentroDeUmaTransacaoEhRecusado` | Contexto não muda no meio de uma transação |
| `…chaveEstrangeiraEscopadaImpedeReferenciaAOutraUnidade` | FK escopada aceita o pai da própria unidade e recusa o de outra |
| `ApplicationInfoControllerTest` (H01) | Contexto completo sobe com Flyway + `validate` no papel de runtime |

### 3.1 Execuções locais (Windows, Git Bash, Temurin 25.0.4.1+1)

- `./mvnw -B -ntp test-compile`: BUILD SUCCESS.
- `./mvnw -B -ntp test -Dtest='Modularity*'`: 3 testes, 0 falhas. Módulos reconhecidos: `platform`, `tenancy`, `establishments`.
- Testes com PostgreSQL: **não executados localmente**. O Docker Desktop desta máquina continua sem iniciar (ver H02). A prova é a CI.
- O download das dependências novas falhou com `PKIX path building failed` (interceptação TLS da rede). Funcionou com `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` só na sessão, sem alterar configuração.

### 3.2 CI (GitHub Actions, PR #3, 11/10/2026)

| Run | Commit | Backend | Frontend | Imagens e smoke |
|---|---|---|---|---|
| 38165808131 | `88ab990` | falhou (4 de 9 em `TenantIsolationIntegrationTest`) | sucesso | skipped |
| 38165969878 | `0d10270` | **sucesso** | **sucesso** | **sucesso** |

Primeira execução: o PostgreSQL recusou exatamente o esperado (`new row violates row-level security policy for table "audit_event"`, `permission denied for table audit_event`, `query would be affected by row-level security policy for table "audit_event"`), mas as asserções verificavam a mensagem da exceção do Spring em vez da causa raiz. Corrigido em `0d10270` sem mudar o comportamento testado. O Testcontainers funcionou no contêiner do job (socket do Docker + `host.docker.internal`) na primeira tentativa.

Execução verde (`0d10270`), runner `ubuntu-24.04`, contêiner Temurin 25.0.4.1:

```
Tests run: 1, Failures: 0 -- in tech.malyah.fastpay.ModularityGateTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.ModularityTest
Tests run: 9, Failures: 0 -- in tech.malyah.fastpay.tenancy.TenantIsolationIntegrationTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.tenancy.MigrationsIntegrationTest
Tests run: 3, Failures: 0 -- in tech.malyah.fastpay.platform.web.ApplicationInfoControllerTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS
```

Smoke Compose no mesmo run:

```
ok http://127.0.0.1:18088/api/v1/platform/info -> {"name":"fastPay","version":"0.1.0-SNAPSHOT","environment":"development"}
ok sem porta publicada
ok 2 migrations; fastpay_app sem SUPERUSER/BYPASSRLS
ok marcador preservado após down/up
```
