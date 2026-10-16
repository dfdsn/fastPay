# H10 — Evidência de cadastro de estabelecimento e convite do dono

Registro sanitizado. Claude Code, 14/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, chaves e senhas de banco geradas a cada execução. Nenhum e-mail real foi enviado: os testes usam a caixa de teste em memória.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V8__estabelecimento_e_convites.sql` | `establishment` ganha `owner_email`, `document` (CNPJ sem pontuação, opcional), `status` (só `CONFIGURACAO` por enquanto) e `created_by`. Tabela `invitation` (unidade, e-mail, papel, quem convidou, hash do token, envio, prazo, aceite, invalidação), um convite em aberto por unidade e e-mail. Funções `current_platform_admin()` e `current_invitation_token_hash()` |
| Papel de admin fastPay | `identity.PlatformAccess`, `PlatformAccessDeniedException` | O papel global é conferido no banco, na transação do trabalho. Policies dão ao admin fastPay só o registro de unidades e convites (criar unidade, ler todas); nada de vínculos, auditoria ou jobs das lojas. Outros recebem 403 `ACESSO_RESTRITO_PLATAFORMA` |
| Cadastro | `establishments.application.EstablishmentRegistry`, `establishments.web.EstablishmentController`, `establishments.domain.Cnpj` | `POST /api/v1/establishments` (nome, e-mail do dono, CNPJ opcional), `GET /api/v1/establishments` e `POST /api/v1/establishments/{id}/owner-invitation` (reenvio). ID interno = UUID gerado pelo servidor. A unidade, o convite do dono (papel `ADMIN_ESTABELECIMENTO`), o job do e-mail e a auditoria (`estabelecimento.criado`, `convite.criado`, `convite.reenviado`) gravam numa transação só, com o contexto da unidade nova. CNPJ numérico ou alfanumérico (formato da Receita a partir de 2026), verificadores conferidos; não é credencial nem chave única |
| Convite | `identity.Invitations`, `identity.application.InvitationEmailJob`, `identity.infrastructure.JdbcInvitationStore` | Como a redefinição de senha da H12: o convite é gravado sem token; o executor do job (escopo da unidade) gera o token, envia pelo modelo de convite e só então grava o hash, o envio e o prazo de 48 h. Convidar de novo o mesmo e-mail na unidade invalida o convite anterior na mesma transação |
| Aceite | `identity.application.InvitationAcceptance`, `identity.web.InvitationController` | `POST /api/v1/invitations/preview` e `POST /api/v1/invitations/accept`, token no corpo. Contexto de RLS `app.invitation_token_hash`: só o convite apresentado é visível. Sem conta: nome e senha (política da P-07) criam identidade, senha e vínculo na transação que consome o convite. Com conta: precisa entrar (com MFA, quando exigido) e aceitar com o e-mail do convite. O convite é travado (`FOR UPDATE`) antes de qualquer gravação: só um aceite vence. Falhas de token contam por origem (`CONVITE_FALHOU`, mesmo limite dos links de redefinição) |
| Seleção de unidade | `identity.web.SessionUnitController` | `GET /api/v1/session/units` informa `platformAdmin`, para a tela mostrar o caminho da área da plataforma |
| Frontend | `invitations/invitation-page.ts`, `platform-admin/establishments-page.ts`, rotas `/convite` e `/admin/estabelecimentos` | Tela de aceite (lê o token do fragmento e o tira da barra de endereço; formulário de conta nova; conta existente pede login; aceite com a sessão). Área do admin fastPay: cadastrar, listar a situação do convite do dono e reenviar |

Sem dependência nova.

## 2. Testes

| Teste | Critério da H10 |
|---|---|
| `EstablishmentOnboardingIntegrationTest.adminFastPayCadastraSemCnpjEmConfiguracaoEConvidaODono` | Admin fastPay cria nome/e-mail/ID sem CNPJ, em configuração; nenhum job além do e-mail do convite (sem mensalidade, cobrança ou ativação); token só como hash; prazo de 48 h do envio; dono cria a senha no aceite, entra com MFA obrigatório e vê a unidade; convite usado não vale de novo; reenvio recusado depois do aceite |
| `…cnpjEOpcionalMasQuandoInformadoPrecisaSerValido`, `CnpjTest` | CNPJ ausente permitido; inválido recusado com campo; numérico e alfanumérico normalizados |
| `…lojaGarcomESessaoAusenteNaoCadastramNemListam` | Tentativa por loja (admin do estabelecimento) e por garçom: 403; sem sessão: 401; admin fastPay na etapa do MFA: 403. Nenhuma unidade criada. Consumidor ainda não tem sessão no sistema (H15/H27): sem sessão de equipe cai no 401 |
| `…papelGlobalNaoAbreDadosOperacionaisDasLojas` | RLS: o admin fastPay lê o registro da unidade, mas não vínculos, auditoria nem jobs das lojas |
| `…conviteVenceEm48HorasDoEnvio` | Token vencido no instante exato do prazo; nada é criado |
| `…reenvioInvalidaOConviteAnterior` | Reenvio invalida o convite anterior; só o novo é aceito; unidade inexistente 404 |
| `…aceitesSimultaneosCriamUmaContaEUmVinculo` | Duplicidade de aceite: quatro aceites ao mesmo tempo, um 201, nenhum 500, uma conta e um vínculo |
| `…contaExistenteEntraParaAceitarComOEmailDoConvite` | Conta existente autentica para vincular; sem sessão não cria outra conta; outra conta é recusada; promovido a admin de loja precisa cadastrar o MFA |
| `…tokensInvalidosRepetidosSaoLimitadosPorOrigem` | Abuso de tokens limitado por origem (429) |
| `MembershipsIntegrationTest.runtimeNaoApagaVinculosNemCriaSemConvite`, `MigrationsIntegrationTest` (ajustados) | Runtime só cria vínculo pelo convite (RLS); 8 migrations, atualização incremental desde a V1 |
| Frontend: `invitation-page.spec.ts`, `establishments-page.spec.ts`, `unit-selection-page.spec.ts` | Token fora da barra de endereço; estados de convite válido, vencido, conta existente, sessão de outra conta e recusa de senha; cadastro sem CNPJ, recusa de CNPJ, reenvio, recusa a quem não é admin fastPay; caminho da área da plataforma |

### Execução

- Backend: `./mvnw clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 e Mailpit por digest), 14/10/2026, árvore de trabalho do commit da H10: **203 testes, 0 falhas** (seção 3).
- Frontend: `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim`, 14/10/2026: lint sem erros, **83 testes Vitest aprovados** (17 arquivos; linhas 96,3%, branches 91,7%), build de produção ok.
- CI do PR: ver o PR da H10.

## 3. Resultado do backend

`./mvnw clean verify`, 14/10/2026 04:43 UTC: **Tests run: 203, Failures: 0, Errors: 0, Skipped: 0**; enforcer, fronteiras Modulith (`establishments` passa a usar só a API pública de `identity`) e gate JaCoCo ok; BUILD SUCCESS. Nesta sessão o Maven Central e o Docker Hub foram acessados pelos espelhos do Google (`maven-central.storage-download.googleapis.com`, `mirror.gcr.io`), com os mesmos digests e o checksum do Maven Wrapper conferido.

## 4. Não executado

- Envio real do convite pelo Gmail de homologação (T-02: conta ainda não existe).
- E2E Playwright (não configurado).
- `compose-smoke.sh` (roda na CI do PR).
