# H04 — Evidência de eventos duráveis, idempotência e jobs

Registro sanitizado. Claude Code, 11/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos: senhas dos testes geradas a cada execução; nenhum e-mail ou PSP real envolvido.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Tabela de intenções | `V3__fila_de_jobs.sql`: `job` | `establishment_id NOT NULL`, RLS ENABLE + FORCE, runtime lê/insere/atualiza só na unidade do contexto, sem DELETE. `UNIQUE (establishment_id, kind, dedup_key)` deduplica o efeito. Estados `PENDING`, `RUNNING`, `SUCCEEDED`, `EXHAUSTED`; `max_attempts` nulo para financeiro; `last_error` até 300 caracteres; `correlation_id`; constraint liga `RUNNING` a lease preenchido |
| Reserva | `claim_due_jobs(limite, lease_s)` (SECURITY DEFINER, dono da tabela) | Única leitura global: devolve só id, unidade e token do lease. `FOR UPDATE SKIP LOCKED` evita dupla reserva; lease vencido de worker caído volta a ser executável; interrompido na última tentativa vai para a fila de falhas. Limites 1..100 jobs e 1..3600 s |
| Auditoria | `audit_event.subject_id` | Retomada registra ação, ator, job e correlationId |
| API pública | `jobs.Jobs`, `JobHandler`, `JobRequest`, `JobExecution`, `JobFailure`, `JobSummary` | `enqueue` exige transação ativa com contexto (grava junto com o fato). `exhausted` lista a fila de falhas da unidade. `retryExhausted` retoma o mesmo job com novo ciclo de tentativas e audita |
| Execução | `application.JobWorker` | Cada job numa transação própria com o contexto da unidade; trava a linha pelo token do lease; efeito de banco e conclusão confirmados juntos. Falha: rollback e, em outra transação, nova tentativa ou fila de falhas |
| Regras | `domain.RetryPolicy`, `domain.SafeError` | 5 tentativas totais (não financeiro), backoff 15 s ×2 até 5 min (configurável); financeiro sem teto. Erro gravado só com o nome da classe, salvo `JobFailure` com mensagem declarada segura |
| Configuração | `application.yaml` `fastpay.jobs.*`, `.env.example` `JOBS_WORKER_ENABLED` | Worker periódico desligável; testes desligam e executam explicitamente |

Entrega ao menos uma vez: efeito fora do banco (e-mail em H12, PSP em H30) precisa ser idempotente pela `dedupKey` ou consultar o estado antes de repetir; o contrato está no Javadoc de `JobHandler`.

## 2. Por que não o registry do Spring Modulith (T-06)

Lido o código de `spring-modulith-events-jdbc` 2.1.1: `event_publication` tem status, `completion_attempts` e `last_resubmission_date`, mas não guarda último erro nem próxima execução, só conta tentativa em reenvio, não tem lease nem teto por tipo e o reenvio é por predicado em memória. Também exigiria que o runtime lesse e alterasse uma tabela global com eventos de todas as unidades. A tabela própria cobre os critérios com SQL testável e RLS por unidade, sem dependência nova. Decisão registrada como T-06 para aceite de Diego.

## 3. Testes

| Teste | Critério da H04 |
|---|---|
| `JobsIntegrationTest.rollbackDoFatoNaoPublicaOJob` | Rollback sem publicação |
| `…intencaoSoPodeSerGravadaNaTransacaoComContexto` | Intenção só dentro da transação do fato, com unidade e tipo registrado |
| `…commitPublicaEWorkerExecutaNoContextoDaUnidade` | Commit publica; worker executa com o contexto e o correlationId do job |
| `…eventoDuplicadoNaoCriaSegundoJobNemSegundoEfeito` | Evento duplicado (antes e depois de concluir); mesma chave em outra unidade é outro efeito |
| `…reinicioEntreCommitEEfeitoRetomaOJob` | Restart entre commit e efeito: reserva sem execução, lease vence, outro ciclo executa (tentativa 2) |
| `…duasExecucoesConcorrentesExecutamCadaJobUmaVez` | Dois workers simultâneos sobre 20 jobs de duas unidades: cada efeito uma vez |
| `…jobEmExecucaoNaoEhRetomadoPorOutroWorkerMesmoComLeaseVencido` | Execução lenta com lease vencido não é duplicada (lock de linha) |
| `…jobSoEnxergaEGravaNaPropriaUnidade` | Isolamento: job não vê dados de outra unidade e não grava nela; runtime sem contexto não vê jobs; reserva só expõe 3 colunas |
| `…naoFinanceiroEsgotaEmCincoTentativasComErroSanitizado` | 5 tentativas, fila de falhas, erro sem dados (o executor lançou texto com número de cartão de teste) |
| `…jobInterrompidoNaUltimaTentativaVaiParaFilaDeFalhas` | Queda na 5ª tentativa não gera 6ª |
| `…financeiroIncertoContinuaAlemDeCincoTentativas` | Financeiro sem teto (7 tentativas, segue pendente) |
| `…retomadaAutorizadaReexecutaAMesmaOperacaoComAuditoria` | Retomada só na unidade dona, uma vez, com auditoria; mesma operação conclui |
| `JobPollingIntegrationTest.workerPeriodicoExecutaJobPublicado` | Worker agendado executa sem chamada explícita |
| `RetryPolicyTest`, `SafeErrorTest` | Regras de tentativa/backoff e sanitização |
| `MigrationsIntegrationTest` (atualizado) | Banco vazio até V3 e atualização V1 → V3 |

### 3.1 Execução local

Comando, da raiz, num contêiner com a mesma imagem da CI e o socket do Docker (Testcontainers):

```
docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD":/work -w /work/backend \
  eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc… ./mvnw -B -ntp clean verify
```

(mais `unzip` instalado, como na CI, e o proxy/CA da sessão). Base antes da mudança, `develop` `96ac6bf`: 17 testes, BUILD SUCCESS. Com a H04:

```
Tests run: 12, Failures: 0 -- in tech.malyah.fastpay.jobs.JobsIntegrationTest
Tests run: 1, Failures: 0 -- in tech.malyah.fastpay.jobs.JobPollingIntegrationTest
Tests run: 4, Failures: 0 -- in tech.malyah.fastpay.jobs.domain.SafeErrorTest
Tests run: 5, Failures: 0 -- in tech.malyah.fastpay.jobs.domain.RetryPolicyTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.tenancy.MigrationsIntegrationTest
Tests run: 9, Failures: 0 -- in tech.malyah.fastpay.tenancy.TenantIsolationIntegrationTest
Tests run: 3, Failures: 0 -- in tech.malyah.fastpay.platform.web.ApplicationInfoControllerTest
Tests run: 2, Failures: 0 -- in tech.malyah.fastpay.ModularityTest
Tests run: 1, Failures: 0 -- in tech.malyah.fastpay.ModularityGateTest
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Cobertura JaCoCo (gate 80/70 em domain/application): `RetryPolicy` 21/21 linhas e 21/22 ramos, `SafeError` 6/6 e 8/8, `JobWorker` 47/49 e 4/4, `JobHandlers` 4/5. Módulos reconhecidos pelo Modulith: `platform`, `tenancy`, `establishments`, `jobs`.

Primeira execução com a H04: 4 falhas, todas de teste (contagem de migrations no teste da H03, consulta de auditoria que pegava também o efeito do teste, e propriedade do worker sobrescrita pela base dos testes). Corrigidas sem mudar o comportamento testado.

### 3.2 CI (GitHub Actions, PR #4, 11/10/2026)

| Run | Commit | Backend | Frontend | Imagens e smoke |
|---|---|---|---|---|
| 38170119581 | `f715888` | **sucesso** | **sucesso** | **sucesso** |

Verde na primeira execução, com os mesmos 39 testes do backend contra PostgreSQL real.

## 4. Fora desta história

- Tela/endpoint da fila de falhas e checagem de permissão na retomada: dependem de identidade e permissões (H07/H11).
- Chave de idempotência de comandos HTTP com hash do payload (especificação §4): primeiro comando crítico (H16).
- Primeiro executor real: e-mail de convite (H12).
