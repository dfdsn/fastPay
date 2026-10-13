# H09 — Evidência de TOTP, códigos de recuperação e bootstrap administrativo

Registro sanitizado. Claude Code, 13/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`/`@exemplo.invalid`, senhas fictícias, chave de MFA e senhas de banco geradas a cada execução. Nenhum token, segredo TOTP ou código de recuperação real aparece aqui.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema | `V6__mfa_e_operador.sql` | `user_mfa` (segredo cifrado, último passo aceito), `mfa_recovery_code` (só hash), `security_event` (trilha da identidade, append-only no runtime), tipo `MFA_FALHOU` em `auth_attempt`. RLS ENABLE + FORCE, policies do runtime só no contexto da própria identidade; o runtime não apaga autenticador. Policies para o papel do Flyway (`CURRENT_USER`) nas tabelas tocadas pelos comandos de operador |
| TOTP | `identity.domain.Totp`, `Base32` | RFC 6238, HMAC-SHA1, 6 dígitos, 30 s, um passo de cada lado; passo igual ou anterior ao último aceito é recusado. Gravação atômica do passo (`UPDATE ... WHERE last_used_step < ?`) |
| Segredo | `identity.infrastructure.MfaSecretCipher` | AES-256-GCM, IV aleatório, id da identidade como dado associado, prefixo `v1:`. Chave `MFA_ENCRYPTION_KEY` obrigatória, sem padrão: sem ela a aplicação não sobe |
| Códigos | `identity.domain.RecoveryCodes` | 10 por cadastro, 16 caracteres sem ambiguidade (cerca de 78 bits), SHA-256 de `identidade:código`, uso único por `UPDATE` condicional, mostrados só na resposta do cadastro |
| Etapas | `identity.domain.AuthStage`, `SessionContext.beginPending/pending`, `MfaRequiredException` | Depois da senha, sessão nova (id novo) com a identidade guardada à parte; nenhuma função aceita (403 com a etapa como código). Vence em 10 min. Concluir cria outra sessão com id novo e os prazos do perfil atual |
| API | `identity.web.MfaController`, `AuthController` | `POST /api/v1/auth/mfa/verify`, `/setup`, `/setup/confirm`, `GET /api/v1/auth/mfa`; login e `GET /api/v1/auth/session` devolvem `stage` |
| Obrigatoriedade e promoção | `AuthStage.mfaRequired`, `SessionExpiryFilter.requireMfaIfNowNeeded` | Obrigatório para admin fastPay, admin do estabelecimento e gerente; opcional para garçom/produção. Sessão autenticada sem segundo fator é reavaliada a cada requisição: promovido (ou com MFA ativado em outra sessão) volta para a etapa antes da operação seguinte |
| Recuperação | `MultiFactor.verify/completeEnrollment` | Senha + código de recuperação abre só o recadastro. Concluir troca segredo e códigos, apaga todas as sessões da pessoa e envia aviso (caixa de teste até H12). Redefinir a senha não toca em MFA |
| Limite | `AuthProperties.mfaFailuresPerAccount` | 5 códigos errados por identidade em 15 min → 429 `MUITAS_TENTATIVAS` (proposta, ver P-07) |
| Operador | `identity.application.OperatorCommands`, `infrastructure.OperatorCommandRunner` | `criar-admin-fastpay` e `redefinir-mfa` com a credencial de migração, fora do processo web; link de uso único de 30 min só na saída padrão; operador e motivo na trilha. Sem cadastro público nem credencial padrão |
| Frontend | `auth/mfa-verify-page.ts`, `auth/mfa-setup-page.ts`, `auth.service.ts`, `signed-in.guard.ts`, `session-expired.interceptor.ts`, `login-page.ts`, `app.ts`, `app.routes.ts` | Telas `/verificacao` e `/configurar-verificacao` (chave em grupos, link `otpauth://`, códigos mostrados uma vez com foco no título), login e guard levam à etapa certa, interceptor leva ao MFA quando o servidor pede no meio da sessão, link "Segurança" no cabeçalho para ativar o MFA opcional |
| Configuração | `application.yaml` (`fastpay.auth.mfa-*`), `.env.example`, Compose dev/HML, `scripts/compose-smoke.sh` | `MFA_ENCRYPTION_KEY` obrigatória; o smoke gera uma por execução |

Sem dependência nova: TOTP, Base32 e AES-GCM usam só a JDK. Sem QR code na tela (exigiria biblioteca nova); a pessoa abre o link `otpauth://` no celular ou digita a chave.

## 2. Testes

| Teste | Critério da H09 |
|---|---|
| `MfaIntegrationTest.gerenteSemAutenticadorPrecisaCadastrarAntesDeQualquerFuncao`, `…adminFastPaySemVinculoTambemPrecisaDeMfa` | MFA privilegiado obrigatório antes de qualquer função; segredo cifrado e códigos só como hash no banco; trilha |
| `…loginComAutenticadorPedeOCodigoETrocaASessao`, `…etapaDoMfaVenceEmDezMinutos` | Segundo passo com id de sessão novo; etapa com prazo curto |
| `…mesmoCodigoNaoValeDuasVezesNemDentroDaJanela`, `…janelaAceitaUmPassoDeCadaLadoENaoMais`, `…codigoDeConfirmacaoDoCadastroNaoServeDeNovoNoLogin`, `TotpTest` (vetores da RFC 6238) | TOTP repetido e janela |
| `…duasVerificacoesSimultaneasComOMesmoCodigoSoUmaEntra` | Código concorrente: 4 verificações simultâneas, só 1 entra |
| `…codigosErradosDemaisBloqueiamAteAJanelaPassar` | Limite de tentativas |
| `…codigoDeRecuperacaoSoAbreORecadastroQueTrocaTudoEncerraSessoesEAvisa`, `…codigoDeRecuperacaoEhDeUsoUnicoEDeUmaSoPessoa`, `RecoveryCodesTest` | Senha + código permite recadastro restrito, invalida antigos e sessões e notifica; uso único |
| `…redefinirASenhaNaoRemoveOMfa` | Reset não remove MFA |
| `…garcomPromovidoAGerenteDuranteASessaoPrecisaCadastrarAntesDaProximaOperacao`, `…garcomComMfaAtivadoEmOutraSessaoTambemConfirmaNestaSessao` | Promoção exige configuração antes de acessar funções, já na sessão aberta |
| `…garcomPodeAtivarMfaPorVontadePropria`, `…cadastroSemIniciarOuSemSessaoEhRecusado` | MFA opcional para operação; erros de fluxo |
| `OperatorCommandsIntegrationTest.bootstrapCriaOPrimeiroAdminSemSenhaQueDefineSenhaEPrecisaCadastrarMfa`, `…bootstrapSoCriaOPrimeiroENaoReaproveitaEmailExistente`, `…comandosValidamEmailNomeOperadorEMotivo` | Bootstrap sem signup público nem credencial padrão |
| `…recuperacaoExcepcionalDoUnicoAdminTiraMfaESenhaEncerraSessoesERegistra` | Perda do único admin conforme o procedimento proposto |
| `…runtimeNaoCriaAdminNemRemoveMfa` | O papel de runtime não cria admin, não apaga MFA e não vê autenticador, códigos ou trilha sem o contexto da identidade |
| `MfaSecretCipherTest`, `Base32Test`, `AuthStageTest` | Cifra presa à identidade e à chave; chave ausente impede a subida; Base32 da RFC 4648; matriz de obrigatoriedade |
| `AuthIntegrationTest`, `SessionUnitControllerTest` (ajustados) | Testes da H07/H08 com gerente e admin agora passam pelo segundo fator |
| Frontend: `mfa-verify-page.spec.ts` (6), `mfa-setup-page.spec.ts` (5), `login-page.spec.ts`, `signed-in.guard.spec.ts`, `session-expired.interceptor.spec.ts` | Navegação por etapa, mensagens em português, códigos mostrados uma vez, MFA pedido no meio da sessão |

## 3. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B -ntp verify` (em `backend/`) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble` (mesma tag da CI) + `unzip`, Docker para o Testcontainers (PostgreSQL 18.6) | **155 testes, 0 falhas**; enforcer, Modulith e JaCoCo ok. Antes da mudança, a mesma execução em `develop` (`85659f7`) dava 109 testes |
| `./scripts/verify.sh frontend` | Contêiner `node:24.18.0-trixie-slim` (mesma tag da CI) | Lint ok; **68 testes Vitest** (linhas 97,1%, branches 92,8%); build de produção ok |
| Smoke com o jar real | `java -jar` (Temurin 25) contra PostgreSQL 18.6 com `infra/postgres/init` | Sem `MFA_ENCRYPTION_KEY`: não sobe, com a mensagem pedindo a chave. `criar-admin-fastpay`: saída 0 e link impresso só na saída padrão (nenhuma linha de log com o token); segunda execução: saída 2, "Já existe um admin fastPay". Pela API: senha definida pelo link, login → `MFA_CADASTRO_OBRIGATORIO`, unidades 403, cadastro com código TOTP calculado à parte → 10 códigos e sessão de 900 s, unidades 200. `redefinir-mfa`: saída 0; banco ficou sem autenticador, sem senha e sem sessões da pessoa; trilha com `admin_fastpay.criado`, `mfa.cadastrado`, `mfa.recuperacao_excepcional` e operador/motivo |

O smoke também mostrou a política de senha em ação: a primeira senha tentada continha a parte local do e-mail (`admin`) e foi recusada com "Não use seu e-mail na senha.".

## 4. Não executado

- `./scripts/compose-smoke.sh`: não rodado nesta sessão (o build das imagens baixa dependências por dentro do Docker); roda na CI do PR, já com `MFA_ENCRYPTION_KEY` gerada pelo script.
- E2E Playwright: ainda não configurado.
- Aplicativo autenticador real num celular: o código foi calculado pelo algoritmo da RFC 6238 em script à parte; o endereço `otpauth://` segue o formato aceito pelos aplicativos, mas não foi lido por um aparelho.
- Envio real do aviso de troca do autenticador: caixa de teste até H12. O aviso da recuperação excepcional feita pelo operador também fica para H12.
