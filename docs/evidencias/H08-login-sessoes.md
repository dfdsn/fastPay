# H08 — Evidência de login da equipe e sessões seguras

Registro sanitizado. Claude Code, 12/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`/`@smoke.invalid`, senhas de teste fictícias e senhas de banco geradas a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema | `V5__credenciais_e_sessoes.sql` | `user_credential` (só hash, uma linha por identidade), `password_reset_token` (só SHA-256 do token, 30 min, um em aberto por identidade), `auth_attempt` (limitação de tentativas, chaves em SHA-256, sem e-mail nem IP em claro) e as tabelas do Spring Session JDBC 4.1.1 migradas pelo Flyway. Todas com RLS ENABLE + FORCE |
| RLS | policies da V5 e `TenantTransactions.forLoginEmail`/`forResetToken` | No login, só a identidade do e-mail digitado é visível, sem credencial; a credencial e os links só aparecem no contexto da própria identidade (`app.identity_id`); ao confirmar a redefinição, só o token apresentado. `auth_attempt` e `spring_session*` são técnicas e globais, com policy ampla explícita (a sessão é procurada pelo cookie antes de qualquer contexto) |
| Login | `identity.application.TeamAuthentication`, `POST /api/v1/auth/login` | E-mail normalizado; conta inexistente, sem senha ou senha errada: mesma resposta 401 `CREDENCIAIS_INVALIDAS` e o hash é sempre verificado (hash falso quando não há conta). Bloqueio antes de conferir a senha: 5 falhas por conta ou 20 por origem em 15 min → 429 `MUITAS_TENTATIVAS`, inclusive para e-mail inexistente. Login certo zera as falhas da conta |
| Sessão | `SessionContext.signIn/signOut/window`, `identity.web.SessionExpiryFilter`, `GET /api/v1/auth/session`, `POST /api/v1/auth/logout` | Spring Session JDBC; login descarta a sessão anterior e cria outra com id novo (fixação); principal da sessão = id da identidade. Perfil mais restritivo alcançável (admin fastPay 15 min/8 h; admin loja/gerente 30 min/12 h; garçom/produção 1 h/12 h). Filtro logo depois do Spring Session encerra sessão vencida por inatividade ou duração absoluta; só requisição sem `X-Fastpay-Background: 1` renova a inatividade. Trocar de unidade não mexe no início absoluto |
| Cookie | `AuthConfiguration.sessionCookie` | `FASTPAY_SESSION`, `HttpOnly`, `Secure` (desligável só por `SESSION_COOKIE_SECURE=false`), `SameSite=Lax`, `Path=/`. Definido por customizador e não por `server.servlet.session.cookie.*`, porque o Spring Boot 4.1 ignora essas propriedades sem servidor embutido (os testes MockMvc recebiam o cookie `SESSION` padrão) |
| CSRF | `AuthConfiguration.securityFilterChain` | Spring Security 7.1 `csrf.spa()`: cookie `XSRF-TOKEN` legível pelo Angular e header `X-XSRF-TOKEN` em toda mutação, inclusive login, logout e redefinição. Recusa em `application/problem+json` 403 `PAGINA_DESATUALIZADA`. Sem login por formulário, HTTP Basic, logout padrão nem usuário em memória (`UserDetailsServiceAutoConfiguration` excluída) |
| Senha | `identity.domain.PasswordPolicy`, `AuthConfiguration.passwordEncoder` | Proposta P-07 (NIST SP 800-63B): mínimo 12 configurável, máximo 64 caracteres/72 bytes, sem regras de composição, recusa de lista local de senhas comuns, de senha com 1–2 caracteres distintos e de senha que contenha o e-mail. Hash `{bcrypt}` custo 12, prefixado para permitir troca de algoritmo |
| Redefinição | `identity.application.PasswordResets`, `POST /api/v1/auth/password-reset` e `/password-reset/confirm` | Pedido sempre 202 com a mesma mensagem. Link de 30 min, uso único (UPDATE condicional), pedido novo invalida o anterior. Senha recusada pela política não consome o link. Concluir troca a senha, encerra todas as sessões da pessoa e não toca em MFA (que ainda não existe; H09 usa tabela própria). Identidade sem senha pode definir a primeira pelo link. Limites: 3 pedidos por conta/h (acima disso responde igual e não envia), 10 por origem/h, 10 links inválidos por origem/h |
| E-mail | `identity.infrastructure.TestMailbox`, `TestMailboxController` | Remetente de teste em memória até H12: nenhum e-mail real. O token vai no fragmento do link (`/redefinir-senha#token=…`), que não chega ao servidor nem aos logs. Leitura da caixa em `GET /api/v1/dev/caixa-de-teste` só com `TEST_MAILBOX_ENDPOINT=true` (ligado só no Compose dev) |
| Frontend | `src/app/auth/*`, `app.ts`, `app.routes.ts`, `app.config.ts`, `status-page.ts` | Telas `/entrar`, `/esqueci-a-senha`, `/redefinir-senha` (Reactive Forms, Material, rótulos, `autocomplete`, erros em `role="alert"`); token lido do fragmento e retirado da barra de endereço; guard de sessão em `/unidades` e `/inicio`; interceptor que, com 401 `SESSAO_AUSENTE`, limpa sessão e unidade e volta ao login; nome e "Sair" no cabeçalho; login limpa dados de unidade de quem usou o aparelho antes |
| Configuração | `application.yaml` (`fastpay.auth.*`, `spring.session.jdbc.initialize-schema=never`, `server.forward-headers-strategy=native`), `.env.example`, Compose dev/HML | `SESSION_COOKIE_SECURE`, `PASSWORD_RESET_LINK_BASE`, `TEST_MAILBOX_ENDPOINT` |

Dependências novas, todas do BOM do Spring Boot 4.1.1 e previstas na especificação §1 (Sessão: Spring Security + Spring Session JDBC): `spring-boot-starter-security` (Spring Security 7.1.1), `spring-boot-starter-session-jdbc` (Spring Session 4.1.1) e `spring-boot-starter-security-test`.

## 2. Testes

| Teste | Critério da H08 |
|---|---|
| `AuthIntegrationTest.loginGuardaSessaoNoBancoComCookieHttpOnlySeguroELax` | Spring Session JDBC (linha com principal = identidade) e atributos do cookie |
| `…loginDescartaSessaoAnteriorEEmiteNovoId` | Fixação: cookie plantado não vira sessão da vítima e a sessão anterior é encerrada |
| `…senhaErradaContaInexistenteOuSemSenhaTemAMesmaResposta`, `…emailComMaiusculasEEspacosEhNormalizado` | Mensagens não enumeram contas |
| `…loginSemTokenCsrfOuComTokenErradoEhRecusado`, `SessionUnitControllerTest.trocarDeUnidadeSemTokenCsrfEhRecusado` | CSRF em login e em mutação autenticada |
| `…logoutEncerraASessaoNoServidor` | Logout revoga a sessão também para cópias do cookie |
| `…gerenteExpiraApos30MinutosSemAtividade`, `…garcomTemUmaHoraDeInatividade`, `…adminFastPayTemQuinzeMinutosEOitoHorasEPrevaleceSobreVinculos`, `…perfilMaisRestritivoEntreAsUnidadesDefineOPrazo` | Expiração por perfil com relógio controlado |
| `…pollingNaoRenovaAInatividade` | Polling não renova |
| `…atividadeConstanteNaoPassaDoLimiteAbsoluto`, `…trocarDeUnidadeNaoEstendeOInicioAbsoluto` | Expiração absoluta |
| `…cincoFalhasBloqueiamAContaMesmoComASenhaCertaAteAJanelaPassar`, `…contaInexistenteTambemEhBloqueadaParaNaoRevelarExistencia`, `…loginCertoZeraAsFalhasDaConta`, `…muitasFalhasDaMesmaOrigemBloqueiamQualquerContaNelaMasNaoEmOutra` | Tentativas abusivas |
| `…redefinirSenhaPeloLinkTrocaASenha`, `…pedidoParaEmailInexistenteTemAMesmaRespostaENaoEnviaNada` | Reset por link; sem enumeração; banco guarda só o hash do token |
| `…linkSoPodeSerUsadoUmaVez`, `…linkVenceEmTrintaMinutos`, `…novoPedidoInvalidaOLinkAnterior`, `…duasConfirmacoesSimultaneasDoMesmoLinkSoUmaTrocaASenha` | Reset repetido, expirado, substituído e concorrente |
| `…redefinirEncerraTodasAsSessoesDaPessoaESoDela` | Reset revoga sessões |
| `…senhaRecusadaPelaPoliticaMantemOLinkValido`, `…identidadeSemSenhaDefinePelaPrimeiraVezPeloLink`, `…pedidosRepetidosDaMesmaContaParamDeEnviarSemMudarAResposta`, `…linksInvalidosRepetidosDaMesmaOrigemSaoBloqueados` | Política, primeira senha e limites do reset |
| `CredentialsRlsIntegrationTest` (5) | RLS das novas tabelas com o papel de runtime; um link em aberto por pessoa |
| `PasswordPolicyTest` (6), `SessionWindowTest` (6), `AttemptLimitTest` (3) | Regras de domínio: política, prazos por perfil, perfil mais restritivo, janela de tentativas, chaves |
| `SessionUnitControllerTest` (12, reescrito) | Testes da H07 agora entram pelo login real, com cookie de sessão e CSRF, em vez de gravar a identidade direto numa sessão simulada |
| `MigrationsIntegrationTest` (atualizado) | Banco vazio até V5 e atualização V1 → V5 |
| Frontend `auth.service.spec.ts` (4), `login-page.spec.ts` (6), `forgot-password-page.spec.ts` (3), `reset-password-page.spec.ts` (5), `signed-in.guard.spec.ts` (3), `session-expired.interceptor.spec.ts` (3), `app.spec.ts` (+1) | Telas, mensagens, token do fragmento, CSRF antes da primeira mutação, limpeza ao entrar/sair/vencer |

### 2.1 Execução local

Backend, da raiz, no contêiner da CI (`eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc…`) com `unzip`, socket do Docker para o Testcontainers (PostgreSQL 18.6, mesmo digest) e o proxy/CA da sessão: `./mvnw -B -ntp clean verify`, árvore de trabalho da H08 (base `develop` `97dde12`):

```
Tests run: 109, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS
```

Cobertura JaCoCo (gate 80% linhas / 70% branches): `identity/domain` 95,5% / 96,0%; `identity/application` 96,7% / 76,0%; `jobs/domain` e `jobs/application` sem mudança.

Frontend, da raiz, no contêiner `node:24.18.0-trixie-slim@sha256:ae91dcc1…` (mesmo digest da CI): `./scripts/verify.sh frontend` → exit 0; lint sem erros, 13 arquivos e 53 testes Vitest aprovados, build de produção ok.

### 2.2 Smoke com o jar real

`./mvnw package -DskipTests`, PostgreSQL 18.6 com `infra/postgres/init/01-roles.sh` e `java -jar` (Tomcat embutido, porta 18080, `TEST_MAILBOX_ENDPOINT=true`, `SESSION_COOKIE_SECURE=false` por ser HTTP local). Identidade e vínculo criados como superusuário. Sequência com `curl`:

| Passo | Resultado |
|---|---|
| `GET /api/v1/platform/info` | `Set-Cookie: XSRF-TOKEN=…; Path=/` |
| Login de identidade sem senha | 401 `CREDENCIAIS_INVALIDAS` |
| Login sem header CSRF | 403 `PAGINA_DESATUALIZADA` |
| Pedido de redefinição | 202, mensagem genérica; link na caixa de teste |
| Confirmação com o token | 204 |
| Login com a senha nova | 200, `Set-Cookie: FASTPAY_SESSION=…; Path=/; HttpOnly; SameSite=Lax`, perfil `GESTAO` (1800 s) |
| `GET /api/v1/auth/session`, `GET /api/v1/session/units` | 200 com nome e a unidade |
| Logout e nova leitura da sessão | 204, depois 401 `SESSAO_AUSENTE` |

Sem a senha gerada do Spring Security no log. Logs com id da identidade e sem e-mail, senha ou token (conferido por `grep` no log do contêiner: 0 ocorrências do e-mail e do token).

## 3. Não executado e limites

- E2E Playwright: ainda não configurado no projeto.
- Envio real de e-mail: fora do escopo até H12; a caixa de teste não é prova de entrega.
- "Reset mantém MFA": não há MFA até H09; a redefinição não toca em nenhuma tabela além de credencial, links e sessões. H09 deve testar o critério com MFA real.
- Aviso antes de a sessão expirar (especificação §5): a API devolve `idleExpiresAt`/`absoluteExpiresAt`, mas a tela de aviso não faz parte do aceite da H08.
- Perfil de sessão calculado no login: promoção ou rebaixamento de papel durante a sessão só muda o prazo no próximo login (permissões por ação e efeito imediato chegam em H11).
- Rate limit por origem depende do endereço do cliente repassado pelo proxy (`forward-headers-strategy: native`, aceito só de redes internas); conferir na publicação (H50).
