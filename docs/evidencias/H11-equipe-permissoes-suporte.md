# H11 — Evidência de equipe, permissões e concessão de suporte

Registro sanitizado. Claude Code, 16/10/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, chaves e senhas de banco geradas a cada execução. Nenhum e-mail real foi enviado: os testes usam a caixa de teste em memória.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V9__equipe_permissoes_suporte.sql` | `membership_permission` (delegações a garçom, só códigos delegáveis por `CHECK`, uma ativa por vínculo e ação, revogação com autor), `support_grant` (dono concede ao suporte com motivo e prazo), `audit_event.detail` (texto curto), `UPDATE (role)` em `membership` para o runtime (a policy da V4 limita à unidade do contexto), leitura dos nomes da equipe na unidade do contexto e a função `support_identity_by_email` (só devolve id de admin fastPay) |
| Matriz de acesso | `identity.Permission`, `identity.domain.AccessPolicy` | Permissões por ação, do PRD §4.2. Ver a matriz na seção 2 |
| Autorização por comando | `identity.Memberships.inUnit(identidade, unidade, permissão, trabalho)`, `UnitMembership.permissions/require`, `PermissionDeniedException` | Papel e delegações lidos na transação de cada comando: delegar, revogar, trocar papel ou desativar vale na próxima requisição da sessão ativa. Sem a permissão: 403 `PERMISSAO_NEGADA` |
| Equipe | `identity.Team`, `identity.web.TeamController` (`/api/v1/team`) | Ver, convidar papel, reenviar e cancelar convite, trocar papel, desativar e delegar. Ninguém altera o próprio vínculo. Trocar papel e desativar travam ator, alvo e admins ativos e reconferem a autoridade do ator nas linhas travadas: a unidade nunca fica sem admin ativo, nem com duas remoções simultâneas. Auditoria com autor |
| Suporte | `platform.SupportAccess`, `platform.web.SupportController`, `platform.infrastructure.JdbcSupportGrantStore` | Dono concede, lista e encerra; suporte lista as concessões vigentes e consulta a equipe da unidade. Cada consulta é auditada antes de mostrar e roda numa transação `READ ONLY`; o suporte age com a própria identidade, sem vínculo nem unidade de sessão |
| Contexto somente leitura | `tenancy.TenantTransactions.readOnlyInEstablishmentAs` | `SET TRANSACTION READ ONLY` antes do contexto: o PostgreSQL recusa qualquer escrita |
| Frontend | `team/team-page.ts`, `support/support-grants-page.ts`, `support/support-area-page.ts`, rotas `/equipe`, `/acesso-de-suporte`, `/admin/suporte`; links no início da unidade e na escolha de unidade | Botões conforme as permissões da sessão (o servidor decide); recusa mostra a mensagem do servidor; o início reconsulta a unidade para refletir mudanças de papel durante a sessão |

Sem dependência nova.

## 2. Matriz de acesso (conferida em `AccessPolicyTest`)

| Ação | Garçom | Produção | Gerente | Admin do estabelecimento | Delegável a garçom |
|---|:-:|:-:|:-:|:-:|:-:|
| Operar comanda, atender problema | sim | | sim | sim | (já tem) |
| Operar produção | | sim | sim | sim | não |
| Concluir problema, cancelar item, conceder desconto, reabrir comanda, transferir unidades, desfazer entrega, alterar disponibilidade, registrar recebimento externo, recuperar acesso do consumidor, vincular continuação de visita | se delegada | | sim | sim | sim |
| Autorizar devolução, encerrar com débito, iniciar regularização, invalidar recebimento externo | | | sim | sim | **não** |
| Ver equipe, delegar permissões | | | sim | sim | não |
| Gerir equipe (convidar, trocar papel, desativar), conectar conta PSP, autorizar suporte | | | | sim | não |

Admin fastPay sem vínculo não opera equipe nem loja; o suporte só lê com concessão vigente.

## 3. Testes

| Teste | Critério da H11 |
|---|---|
| `AccessPolicyTest` (26, inclui um caso por ação) | Matriz por comando, inclusive devolução não delegável; delegação só a garçom e só de ação delegável; gerente não delega a si nem a outro gerente |
| `TeamIntegrationTest.donoConvidaPapelEPessoaEntraNaEquipe` | Dono convida papel; reenvio invalida o anterior; cancelamento; aceite cria o vínculo com o papel do convite |
| `…gerenteVeEDelegaMasNaoGereEquipe` | Gerente delega só ações permitidas; não convida, não troca papel, não desativa (403); ação não delegável recusada (422) |
| `…permissaoDelegadaERevogadaValeNaSessaoAtiva` | Delegar e revogar mudam as permissões da sessão já aberta do garçom |
| `…desativacaoTiraOAcessoNaSessaoAtivaEPreservaAutoria` | Desativação vale na sessão ativa; vínculo não é apagado; autor auditado |
| `…promocaoExigeAutenticadorERevogaDelegacoes` | Promoção exige MFA na próxima requisição; delegações do garçom promovido são revogadas |
| `…ninguemAlteraOProprioVinculo` | Último admin preservado: não se rebaixa nem se desativa |
| `…administradoresQueSeRebaixamAoMesmoTempoDeixamUmAtivo` | Concorrência: dois admins se rebaixam ao mesmo tempo, um passa e um é recusado; resta um admin |
| `…outraLojaNaoEAlcancadaPorId`, `…adminFastPaySemVinculoNaoOperaAEquipe` | Vínculo de outra loja por id: 404; admin fastPay sem vínculo: 403 |
| `SupportAccessIntegrationTest.donoConcedeESuporteConsultaSoLeituraComAutoriaPropria` | Concessão com motivo e prazo, consulta com a identidade do suporte, auditoria de concessão, consulta e encerramento |
| `…consultaDoSuporteRodaSomenteLeituraNoBanco` | Suporte tentando escrever: o banco recusa (`read-only transaction`) |
| `…semConcessaoVigenteNaUnidadeOSuporteERecusado` | Sem concessão, outra loja, unidade inexistente, encerrada e vencida: mesma recusa |
| `…suporteQueDeixaDeSerAdminFastPayPerdeOAcesso` | Perder o papel global corta a concessão |
| `…soODonoConcedeEOEmailPrecisaSerDaEquipeFastPay` | Só o dono concede; e-mail precisa ser de admin fastPay; motivo e prazo validados |
| `MembershipsIntegrationTest.runtimeNaoApagaVinculosNemCriaSemConvite` (ajustado), `MigrationsIntegrationTest` | Troca de papel pelo runtime só alcança a unidade do contexto; vínculo não muda de pessoa; 9 migrations |
| Frontend: `team-page.spec.ts`, `support-grants-page.spec.ts`, `support-area-page.spec.ts`, `unit-home-page.spec.ts` | Telas do dono e do gerente, recusa do último admin, delegação, concessão e encerramento do suporte, consulta só leitura, links por permissão |

### Execução

- Backend: `./mvnw clean verify` no contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 e Mailpit por digest), 16/10/2026 22:43 UTC, árvore de trabalho do commit da H11: **Tests run: 243, Failures: 0, Errors: 0, Skipped: 0**; enforcer, fronteiras Modulith e gate JaCoCo ok; BUILD SUCCESS.
- Frontend: `./scripts/verify.sh frontend` no contêiner `node:24.18.0-trixie-slim`, 16/10/2026: lint sem erros, **102 testes Vitest aprovados** (20 arquivos; linhas 96,85%, branches 91,43%), build de produção ok.
- CI do PR: ver o PR da H11.

## 4. Limitações e não executado

- O perfil de inatividade da sessão (30 min para gerente/admin, 1 h para garçom) só muda no próximo login depois de uma troca de papel; a exigência de MFA vale na hora.
- O suporte consulta por enquanto só a equipe e os convites; as demais consultas (comandas, produção, pagamentos) entram com as histórias que criam esses dados, pelo mesmo `SupportAccess.consult`.
- Proposta P-16 (quem é suporte, prazo máximo de 72 h) aguarda Diego.
- E2E Playwright (não configurado); `compose-smoke.sh` (roda na CI do PR); envio real de convite pelo Gmail de homologação (T-02).
