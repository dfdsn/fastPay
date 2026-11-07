# H27 — Evidência do login Google opcional e do histórico do consumidor

Registro sanitizado. Claude Code, 07/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: contas Google simuladas (`sub-…`, e-mails `@exemplo.com`), identidades da equipe com e-mail `@teste.invalid` e senhas fictícias. Nenhum cliente OAuth real do Google foi usado.

Desenvolvida em paralelo com a H28 a pedido de Diego (07/11/2026, exceção registrada à D-28); migrations combinadas com o thread da H28 (H28 = V23, H27 = V24).

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V24__conta_google_e_historico.sql` | `consumer_account` (chave = "sub" do Google; e-mail, se é verificado, versão e data do aviso aceito, criação e último login; sem nome, foto ou telefone), separada de `user_identity`. `consumer_tab_link`: uma conta por comanda, com a comanda, a unidade e o acesso avulso usados. `auth_attempt` ganha `GOOGLE_INICIADO`. Contextos de RLS `app.google_subject` (só a conta desse "sub") e `app.consumer_account_id` (a própria conta, os próprios vínculos, as comandas vinculadas e o nome da loja delas). A unidade vê só os vínculos das comandas dela, nunca a conta |
| Login Google | `identity.application.GoogleSignIn`, `identity.infrastructure.GoogleOidcClient`, `GoogleIdTokenVerifier`, `ConsumerProperties`, `identity.web.ConsumerController` | OpenID Connect com código de autorização e PKCE S256; escopos `openid email`; state, nonce e verificador guardados na sessão do servidor, de uso único e 10 min; troca do código só entre servidor e Google (segredo fora do Git); ID token conferido (assinatura pelas chaves do Google, emissor com e sem `https://`, público e `azp` = nosso client id, validade com 60 s de folga, nonce). Nenhum token do Google é guardado ou registrado em log. Retorno para telas do consumidor apenas (sem redirect aberto). Sem `GOOGLE_CLIENT_ID` o botão avisa que não está disponível |
| Aviso de privacidade | `GoogleSignIn.accept/decline`, `frontend/src/app/consumer/privacy-page.ts` | Primeiro login mostra o aviso (o que guardamos, quem vê, como pedir exclusão); a conta só é criada no aceite, com a versão; recusar não guarda nada. Texto provisório até a validação profissional (P-03/P-08) |
| Sessão do consumidor | `identity.ConsumerSession`, `identity.web.ConsumerSessionFilter`, `SessionProfile.CONSUMIDOR` | Spring Session com atributos próprios; 7 dias sem ação e 30 dias no total; polling/SSE não contam. Entrar como consumidor descarta a sessão anterior (inclusive da equipe) e o login da equipe descarta a do consumidor: nenhuma fusão nem passagem de privilégio, mesmo com o mesmo e-mail |
| Vínculo e histórico | `tabs.ConsumerHistory`, `tabs.GuestAccess.inTabAsConsumer`, `JdbcConsumerLinkStore`, `tabs.web.ConsumerHistoryController` | Guardar exige, no mesmo navegador, o acesso avulso válido à comanda (conferido na mesma transação do vínculo), a conta conectada e `confirmed: true`; vale também com a comanda encerrada enquanto o acesso vale (FR-10/FR-11). Repetição pela mesma conta não muda nada; outra conta 409. Auditoria `consumidor.comanda_vinculada` e aviso `COMANDA`. Histórico paginado (20, até 100), da comanda aberta mais recente para a mais antiga, com loja, número, apelido e datas. A equipe consulta só a marca `registered` |
| Frontend | `consumer/consumer.service.ts`, `privacy-page.ts`, `history-page.ts`, `tab-history-panel.ts`, `google-result.ts`; `guest/guest-tab-page.ts`, `guest/guest-tabs-page.ts`, `app.routes.ts` | Na comanda do cliente: "Entrar com Google" (opcional) ou "Confirmo que esta comanda é minha" + "Guardar no meu histórico"; tela "Meu histórico" com paginação e "Sair da conta Google"; mensagens do retorno (`?google=conectado|recusado|falhou|indisponivel|limite`) |
| Configuração | `application.yaml` (`fastpay.consumer.*`), `.env.example`, `infra/compose.dev.yml`, `infra/compose.hml.yml` | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` |

Dependência nova: `org.springframework.security:spring-security-oauth2-jose` (módulo do Spring Security, versão pelo BOM do Boot; traz o Nimbus JOSE+JWT) para conferir o ID token. Não usa `spring-boot-starter-oauth2-client`, que traria filtros de login próprios em conflito com a sessão do fastPay.

## 2. Decisões aprovadas

- **P-13** (aprovada por Diego em 07/11/2026, cartão de decisão no thread da H27, "Aprovar como proposta"): escopos `openid email`; chave `sub`; guardar só `sub`, e-mail, verificação, aceite (versão e data) e datas de criação/último login; conta separada da equipe sem fusão nem "associar" no MVP; aviso de privacidade antes do primeiro login; histórico só de comandas vinculadas com acesso válido e confirmação; a loja vê só a marca "cadastrado"; retenção pela P-08 (H47). Parte do PSP fica para a H30.
- **T-03 da H27** (aprovada por Diego em 07/11/2026, cartão, "Aprovar como proposto"): state/nonce/PKCE 10 min e uso único; 20 logins iniciados por origem em 15 min; histórico 20 por página, até 100; configuráveis em `fastpay.consumer.*`.

## 3. Testes

| Teste | Critério da H27 |
|---|---|
| `ConsumerGoogleIntegrationTest.pedidoAoGoogleUsaEscoposMinimosStateNoncePkceERetornoSeguro` | Escopos `openid email`, state/nonce de 256 bits, PKCE S256, endereço de retorno; retorno para outro site trocado por "Minhas comandas" |
| `…primeiroLoginMostraAvisoDePrivacidadeERecusarNaoGuardaNada` | **Privacidade**: sem conta antes do aceite; recusar não cria conta, aceite depois 410; histórico 401 `CONSUMIDOR_SEM_LOGIN` |
| `…aceitarOAvisoCriaContaSoComDadosMinimosEProximoLoginEntraDireto` | Aviso de outra versão 409; conta só com as colunas mínimas, e-mail normalizado, versão aceita; segundo login em outro aparelho entra direto, sem nova conta |
| `…googleNegadoOuFalhoDeixaAvulsoEStateValeUmaVezPorDezMinutos` | **OAuth negado/com falha**: negado, sem pedido, state diferente, repetido, de outro navegador, troca/ID token inválido e pedido vencido; ninguém conectado |
| `…avisoDePrivacidadeAguardaSoDezMinutos` | Etapa do aviso vence |
| `…loginsIniciadosPorOrigemTemLimite` | 20 por origem em 15 min, depois `?google=limite`; janela libera |
| `…contaGoogleComEmailDaEquipeNaoSeFundeComAEquipe` | **Sem fusão**: mesmo e-mail do garçom vira conta de consumidor à parte; a sessão da equipe cai e a da equipe derruba a do consumidor |
| `…loginGoogleSemAcessoAComandaNaoDaAcessoNenhum` | **Login sem grant**: 404 para ver/guardar a comanda; histórico vazio |
| `…vincularExigeLoginConfirmacaoEUmaContaPorComanda` | Avulso sem login 401; acesso avulso continua após o login; sem confirmação 400; **associação repetida** não duplica (uma auditoria); **conta Google diferente** 409 e a comanda continua da primeira |
| `…acessoRecuperadoPelaEquipeRevogaOAnteriorParaVincular` | Acesso revogado pela recuperação (P-07) não vincula |
| `…historicoMostraComandasDaPessoaEmTodasAsLojasSemExporTerceiros` | Histórico entre lojas, ordem, paginação e teto 100; comanda encerrada com acesso válido entra; outra pessoa não vê; loja vê só `registered` (sem e-mail); outra loja 404 |
| `…rlsSeparaContasVinculosELojas` | RLS no banco: loja não lê `consumer_account`; vínculos só da loja; consumidor lê só a própria conta, as comandas e lojas vinculadas, nada de `guest_grant`; vínculo para outra conta recusado |
| `…sessaoDoConsumidorVence7DiasParadaE30DiasNoTotal` | **Prazos de sessão**: tráfego automático não renova; 7 dias parado vence; com uso, vence aos 30 dias |
| `GoogleIdTokenVerifierTest` (9) | Token certo devolve só sub/e-mail/verificação; emissor sem `https://`; recusa nonce, público, `azp`, emissor, assinatura de outra chave, malformado, vencido após a folga, sem sub ou e-mail |
| `MigrationsIntegrationTest` | 23 migrations em banco vazio (V1–V22 e V24; a V23 é da H28) e 22 a partir da V1 |
| `consumer/consumer.spec.ts` (10) | Painel sem login (entrar e volta para a comanda), confirmação obrigatória e corpo `confirmed: true`, comanda de outra conta, recusa do servidor; aviso aceito/recusado/expirado; histórico com paginação e sair; histórico sem login; retorno só para telas do consumidor |

Ajuste de infraestrutura de teste: `PostgresIntegrationTest` sobe o PostgreSQL de teste com `max_connections=300`, porque o contexto Spring novo da H27 passou do limite padrão de 100 conexões somado aos pools dos contextos em cache (falha "remaining connection slots are reserved" na primeira execução completa).

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `9774f4f` | 500 testes, 0 falhas, cobertura atendida |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | Contêiner `node:24.18.0-trixie-slim` | idem | 39 arquivos, 218 testes, lint e build verdes |

Não executados: login com uma conta Google real (falta o cliente OAuth de teste no Google Cloud, a cargo de Diego; até lá a troca do código foi simulada e a conferência do ID token testada com chave própria), `compose-smoke.sh` e imagens (CI do PR), E2E Playwright (não configurado), celular real na homologação de Diego.
