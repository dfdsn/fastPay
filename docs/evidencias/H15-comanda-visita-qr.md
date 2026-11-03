# H15 — Evidência de comanda individual, visita e QR avulso

Registro sanitizado. Claude Code, 03/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, tokens aleatórios gerados a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V12__comanda_visita_acesso.sql` | `visit`; `tab` (número 1–9999 único entre as ativas por índice parcial, nome 1–60, mesa opcional 1–20, `settings_version_id` com FK composta para a versão da H14, `closed_at` e `access_until` para as histórias de fechamento); `binding_token` (só SHA-256, prazo, no máximo um em aberto por comanda); `guest_grant` (só SHA-256, um ativo por comanda por índice parcial); `access_recovery` (conferência e motivo da P-07). RLS `ENABLE + FORCE`; o consumidor sem sessão só enxerga a linha do hash apresentado (`app.binding_token_hash`, `app.guest_grant_hash`) e o resto roda no contexto da unidade do token |
| Regras puras | `tabs.domain.TabRules`, `GuestAccessRules`, `AccessTokens`, `QrSvg` | Nome/mesa normalizados; conferência da recuperação; unidade ativa em produção; QR utilizável; acesso válido e prazo de 7 dias com suspensão; token de 256 bits e hash; SVG só com retângulos |
| Equipe | `tabs.Tabs`, `tabs.web.TabController` (`/api/v1/tabs`) | Abrir, listar, consultar, trocar mesa e gerar QR com `OPERAR_COMANDA`; recuperar com `RECUPERAR_ACESSO_CONSUMIDOR`. Número escolhido sob `pg_advisory_xact_lock` por unidade; comanda e QR travados (`FOR UPDATE`) |
| Consumidor | `tabs.GuestAccess`, `tabs.web.GuestController` (`/api/v1/guest`) | Prévia sem consumir, confirmação atômica (QR travado e consumido, acesso inserido, índice único como última barreira), comandas deste navegador e comanda por id só com acesso válido |
| Unidade | `establishments.UnitProfiles` | Nome e situação da unidade do contexto (ativa só depois da H39) |
| Frontend | `tabs/tabs-page.ts` (`/comandas`), `guest/binding-page.ts` (`/c`), `guest/guest-tabs-page.ts` (`/minhas-comandas`), `guest/guest-tab-page.ts` (`/minha-comanda/:id`), link no início | Abrir e já mostrar o QR com contagem regressiva; gerar outro; trocar mesa; recuperar com conferência; cliente vê de quem é o QR e confirma só no botão; o token sai da barra de endereço |
| Dependência | `io.nayuki:qrcodegen` 1.8.0 (MIT, puro Java, sem dependências) | Matriz do QR; o SVG é montado pelo fastPay. Proposta T-01 |

## 2. Decisões de Diego em 03/11/2026 (cartões no thread da H15)

| Decisão | Conteúdo |
|---|---|
| P-07, recuperação avulsa | Quem tem `RECUPERAR_ACESSO_CONSUMIDOR` (gerente, admin ou garçom com a delegação) vê a pessoa; ela diz o nome/apelido da comanda e um item pedido (ou, sem pedidos, a hora da abertura); motivo escrito (5–300); auditado; o acesso anterior é revogado e um QR novo é gerado. Alternativas oferecidas: só gerente/admin; só presencial + motivo |
| Abertura antes da ativação | Unidade em configuração abre comanda só em desenvolvimento e homologação (`fastpay.environment`); em produção só unidade ativa (H39). Alternativa oferecida: comandas de teste também em produção |

## 3. Propostas aprovadas por Diego em 03/11/2026 (cartão no thread da H15, depois do merge do PR #19)

| Proposta | Conteúdo |
|---|---|
| T-01 | `io.nayuki:qrcodegen` 1.8.0 para desenhar o QR no servidor (alternativa: biblioteca JavaScript no frontend) |
| T-03 (H15) | Nome/apelido até 60 caracteres; mesa até 20; números de 1 a 9999 (menor livre entre as ativas); motivo da recuperação de 5 a 300; cookie do acesso `FASTPAY_ACESSO` (caminho `/api/v1/guest`, `HttpOnly`, `SameSite=Lax`, `Secure` fora do dev) guardado por 180 dias com até 10 comandas por navegador. Sem limite de tentativas no QR: o token tem 256 bits e vale 2 minutos |
| Momento da revogação na recuperação (registrado; não fazia parte do cartão) | O acesso anterior cai quando a recuperação é registrada (antes de o cliente ler o QR novo). Se o QR novo vencer, qualquer pessoa com `OPERAR_COMANDA` gera outro, porque a comanda já está sem acesso |

## 4. Testes

| Teste | Critério da H15 |
|---|---|
| `TabRulesTest` (4) | Nome e mesa normalizados e limitados; recuperação exige nome, item/hora e motivo; só unidade ativa abre em produção |
| `GuestAccessRulesTest` (4) | QR vence em 2 min e não vale usado/substituído; acesso sem prazo vale até revogado; revogação prevalece; pendência suspende e resolução reinicia 7 dias (CA-23) |
| `AccessTokensTest` (2), `QrSvgTest` (1) | 256 bits sem repetir, só o hash guardado, formato errado recusado; SVG sem o texto do link nem script |
| `TabsIntegrationTest.garcomAbreComandasComNumeroAutomaticoNomeRepetidoEMesaOpcional` | Número automático, nome repetido, mesa opcional, visita por comanda, **versão da configuração capturada e mantida após mudança**, número liberado ao encerrar, auditoria |
| `…aberturasSimultaneasNaoRepetemNumero` | 6 aberturas simultâneas recebem 1–6 |
| `…soQuemOperaComandaAbreEVeEDadosInvalidosSaoRecusados` | Produção 403; nome e mesa inválidos 400 com `field`; comanda de outra unidade 404; **sem sessão de equipe 401: consumidor não abre comanda (CA-01)** |
| `…trocarMesaNaoMudaTitularEPedeVersaoAtual` | Titular e número iguais; versão desatualizada 409; retirar a mesa |
| `…qrVinculaUmaVezEPreviewNaoConsome` | **Prévia não consome**; confirmação cria o acesso; **mesmo QR em outro aparelho 410**; com acesso ativo, outro QR é recusado |
| `…acessoSobreviveAFecharONavegadorESoDaAPropriaComanda` | **Cookie persistente (`Max-Age`), `HttpOnly`, só em `/api/v1/guest`; navegador reaberto só com o cookie vê a comanda**; **outra comanda (nome/número) negada**; token forjado negado; duas comandas no mesmo navegador |
| `…novoQrSubstituiOAnteriorEQrVenceEmDoisMinutos` | **QR substituído** 410; **QR expirado** aos 120 s; gerar outro depois de vencer |
| `…qrConfirmadoAoMesmoTempoEmDoisAparelhosVinculaSoUm` | **QR simultâneo**: 4 confirmações paralelas, um só acesso |
| `…recuperacaoExigePermissaoEConferenciaERevogaOAcessoAnterior` | Garçom sem delegação 403; conferência incompleta 400 por campo; com delegação recupera; **acesso anterior revogado na hora**; novo aparelho confirma; comanda inalterada; registro e auditoria (CA-02) |
| `…recuperarSemAcessoAtivoBastaMostrarOQr` | Recuperação sem acesso ativo 409 |
| `…acessoAvulsoValeAteSeteDiasDepoisDaResolucaoEPendenciaSuspendeOPrazo` | **Encerrada com pendência: acesso continua 30 dias depois; resolvida: vale até resolução + 7 dias e acaba no segundo seguinte; cookie limpo** (prazo gravado direto no banco, porque encerramento/chamado são das H21/H28–H32) |
| `…acessoDoConsumidorNaoVeComandaDeOutraUnidadeNemComTokenTrocado` | Consumidor e equipe de outra unidade não alcançam a comanda |
| `MigrationsIntegrationTest` (3) | V1–V12 em banco vazio e de V1 para V12 |
| Frontend `tabs-page.spec.ts` (6), `binding-page.spec.ts` (3), `guest-tab-page.spec.ts` (3), `unit-home-page.spec.ts` | Abrir e mostrar QR com prazo; sem nome não envia; recuperação só com permissão e conferência completa; troca de mesa com versão e conflito; produção sem acesso; prévia sem consumir e confirmação no botão; QR inválido; link sem token; comanda do cliente e recusa; link Comandas no início |

Fora do escopo e não simulado: "fixture ativa de teste não substitui H39" — nenhum teste marca unidade como ativa; o bloqueio em produção é provado pela regra pura `TabRules.unitMayOpenTabs` e pela leitura da situação real da unidade.

## 5. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B -ntp clean verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 da imagem fixada), sessão em nuvem, 03/11/2026 | **346 testes, 0 falhas**; enforcer, Modulith e JaCoCo ok |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 03/11/2026 | lint ok; **137 testes**, 0 falhas; build ok (aviso NG8102 já existente em `team-page.ts`) |
| CI do PR #19 (commit `26edaa7`) | GitHub Actions, 03/11/2026 | backend, frontend, imagens e smoke Compose: sucesso |
| CI de `develop` após o merge (`d3ec085`) | GitHub Actions, run 41672570090, 03/11/2026 | sucesso |

Não executados: `scripts/compose-smoke.sh` e build das imagens (rodam na CI do PR; sem mudança de Dockerfile/Compose); E2E Playwright (não configurado); leitura do QR com a câmera de um celular real e teste manual na homologação da máquina de Diego.
