# H12 — Evidência de convites e e-mail durável em homologação

Registro sanitizado. Claude Code, 13/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`/`@permitido.invalid`/`@permitido.test`, senhas fictícias, chaves e senhas de banco geradas a cada execução. Nenhum e-mail real foi enviado: o SMTP foi provado contra um servidor de teste em contêiner (Mailpit), sem destinatário real.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Fila por identidade | `V7__jobs_por_identidade.sql`, `jobs.Jobs`, `jobs.infrastructure.JdbcJobStore`, `jobs.application.JobWorker`, `jobs.JobExecution` | E-mail da conta (redefinição, aviso de MFA) não tem unidade. `job` passa a ter exatamente um dono: `establishment_id` (H04) ou `identity_id`. Policy nova só no contexto da própria identidade; nenhuma unidade vê jobs de conta. `claim_due_jobs` devolve também a identidade e o worker abre o contexto certo. Mesma deduplicação, tentativas, fila de falhas e lease da H04 |
| Porta de e-mail | `mail.application.EmailPort`, `mail.infrastructure.SmtpEmailPort`, `TestMailbox`, `MailConfiguration` | `MAIL_TRANSPORT=caixa-de-teste` (padrão; memória, nenhum envio) ou `smtp` (Gmail dedicado em HML; SES por SMTP em H49). SMTP com STARTTLS obrigatório e prazo de 10 s para conectar, ler e escrever (o envio roda dentro da transação do job) |
| Envio | `mail.Emails` | Lista de destinatários permitidos; fora de produção marca o ambiente no assunto (`[Homologação]`) e no corpo; `Message-ID` estável por job (mesmo em todas as tentativas); falha do servidor vira `JobFailure` com mensagem segura (só o nome da exceção, sem servidor, conta nem destinatário); métrica `fastpay.email{resultado=enviado|bloqueado|falhou}` |
| Modelos | `mail.EmailTemplates` | Convite (quem convidou, unidade, papel, prazo, uso único), redefinição de senha (30 min, até quando) e aviso de MFA trocado. Texto simples em português, horário de Brasília |
| Links | `mail.PublicLinks` | `PUBLIC_BASE_URL` + `/redefinir-senha#token=` ou `/convite#token=`; token no fragmento. Substitui `PASSWORD_RESET_LINK_BASE` (H08), também no link do comando de operador |
| Configuração segura | `MailConfiguration.validate` | Não sobe: SMTP sem `SMTP_HOST`/`SMTP_USERNAME`/`SMTP_PASSWORD`/`MAIL_FROM` (mensagem nomeia o que falta, sem valores); SMTP fora de produção sem `MAIL_ALLOWED_RECIPIENTS`; produção com caixa de teste ou link sem https; `TEST_MAILBOX_ENDPOINT` fora de desenvolvimento ou sem a caixa de teste |
| Redefinição durável | `identity.application.PasswordResets`, `PasswordResetEmailJob` | O pedido grava só a intenção (payload `{}`) na transação da identidade. O executor cria o token na hora do envio, manda o e-mail e só então grava o hash e invalida o link anterior: nenhum token em claro fica no banco nem na fila; destinatário bloqueado não ganha link; o prazo de 30 min conta do envio |
| Aviso de MFA durável | `MultiFactor.completeEnrollment`, `MfaReplacedEmailJob` | Enfileirado na mesma transação do recadastro e do registro em `security_event` |
| Caixa de teste dev | `mail.web.TestMailboxController` | Mesmo endereço `GET /api/v1/dev/caixa-de-teste`; propriedade passa a `fastpay.mail.test-mailbox-endpoint` (mesma variável `TEST_MAILBOX_ENDPOINT`) |
| Configuração | `application.yaml` (`fastpay.mail.*`), `.env.example`, Compose dev/HML | Dev: caixa de teste legível. HML: caixa de teste por padrão; envio real só com `MAIL_TRANSPORT=smtp`, `SMTP_*`, `MAIL_FROM` e `MAIL_ALLOWED_RECIPIENTS` no `.env.hml` |

Dependência nova: `spring-boot-starter-mail` (Jakarta Mail/Angus pelo BOM do Boot 4.1.1), prevista na especificação §9 (Gmail SMTP em HML, SES em produção). Imagem de teste nova: `axllent/mailpit:v1.27.10` fixada por digest, só nos testes.

## 2. Testes

| Teste | Critério da H12 |
|---|---|
| `AccountEmailIntegrationTest.falhaDoServidorTentaCincoVezesFicaNaFilaDeFalhasEOReenvioFunciona` | Falha SMTP; cinco tentativas; erro visível e sanitizado na fila de falhas (`EXHAUSTED`, `last_error` sem servidor nem destinatário); nenhum link válido criado; reenvio pelo mesmo caminho (e limites) do pedido original funciona |
| `…repeticaoDepoisDeOServidorAceitarUsaOMesmoMessageIdESoOUltimoLinkVale` | Reenvio idempotente: servidor aceitou e a conexão caiu; a repetição leva o mesmo `Message-ID` e só o último link vale |
| `…destinatarioForaDaListaNaoRecebeNemGanhaLink`, `EmailsTest.destinatarioForaDaListaNaoRecebe`, `RecipientAllowlistTest` | Destinatário indevido bloqueado (exato ou domínio, sem sufixo parecido) |
| `…prazoDoLinkContaDoEnvioEVenceEmTrintaMinutos` | Link expirado: vale 30 min a partir do envio |
| `…jobDaContaSoAparecePraPropriaPessoa` | RLS do job por identidade; fila sem link nem token |
| `MailConfigurationTest` | Segredo ausente impede a subida; lista obrigatória com SMTP fora de produção; produção sem caixa de teste e com https; caixa legível só em dev; endereço público válido |
| `SmtpEmailPortIntegrationTest` (Mailpit) | Envio SMTP real com assunto UTF-8 e `Message-ID` do efeito; STARTTLS exigido; servidor fora do ar vira exceção |
| `EmailsTest`, `EmailTemplatesTest` | Marca do ambiente, produção sem marca, falha sanitizada, métricas; links no endereço público com token no fragmento; textos de convite, redefinição e aviso |
| `AuthIntegrationTest`, `MfaIntegrationTest` (H08/H09, ajustados) | Redefinição e aviso de MFA agora passam pela fila; comportamento anterior mantido |
| `JobsIntegrationTest`, `MigrationsIntegrationTest` (ajustados) | `claim_due_jobs` devolve 4 colunas; 7 migrations, atualização incremental desde a V1 |

### Execução

- Backend: `./mvnw -B -ntp verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesmo digest da CI), Docker para o Testcontainers (PostgreSQL 18.6 e Mailpit, por digest), 13/10/2026, árvore de trabalho do commit da H12: **179 testes, 0 falhas**, enforcer, Modulith (módulo `mail` novo) e JaCoCo ok, BUILD SUCCESS. Nesta sessão o Maven Central e o Docker Hub responderam 429; usados os espelhos do Google (`maven-central.storage-download.googleapis.com`, `mirror.gcr.io`) com os mesmos digests e o checksum do Maven Wrapper conferido.
- Frontend: sem mudança nesta história; roda na CI do PR.
- Compose smoke: roda na CI do PR (Compose dev sobe com a caixa de teste e a configuração nova).

## 3. Não executado e por quê

- Envio pelo Gmail de homologação: a conta dedicada e a senha de app não existem ainda (T-02, Diego). O caminho SMTP foi provado contra o Mailpit; o primeiro envio real deve ir só para endereços da lista permitida.
- Status de entrega separado (bounce/complaint): SMTP do Gmail não informa; fica para o SES (H49).
- Fluxo do convite (token de 48 h, aceite, reenvio que invalida o anterior, autorização de quem reenvia): pertence à H10/H11, que criam o convite. A H12 entrega o modelo, o link e o envio durável que elas usam; o reenvio de convite deve passar pela mesma autorização de quem convidou e gerar efeito novo na fila.
- Aviso por e-mail no `redefinir-mfa` do operador: depende da aprovação da proposta P-07.
- Tela da fila de falhas e retomada autorizada de jobs de conta: chegam com as permissões de suporte/admin (H11). Hoje a falha fica visível no banco (`job.status = 'EXHAUSTED'`, `last_error`), no log (`ERROR`) e na métrica.
