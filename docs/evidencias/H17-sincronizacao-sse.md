# H17 — Evidência de sincronização operacional SSE e reconexão

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, tokens de acesso aleatórios gerados a cada execução.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V14__sincronizacao_operacional.sql` | `sync_change` (unidade, posição, tópico `COMANDA`/`PEDIDO`/`PRODUCAO`, comanda, instante; sem conteúdo) e `sync_cursor` (última posição da unidade). A posição é incrementada com a linha da unidade travada até o commit: sequência densa, na ordem dos commits, devolvida no rollback. RLS `ENABLE + FORCE`; runtime lê e grava só no contexto da unidade; aviso não muda (só a retenção apaga) |
| Publicação | `sync.Changes.publishInCurrentTransaction` | Chamada no fim da transação do fato: `Tabs.open/changeTable/recover` e `GuestAccess.confirm` (`COMANDA`), `Orders.send/retryForwarding` (`PEDIDO`), `ProductionIntake` no commit do job (`PRODUCAO`). Retenção a cada 200 posições, no contexto da unidade |
| Leitura | `sync.Changes.read` | Avisos depois do cursor, ou `resync` quando o cursor não serve: ausente, inválido, à frente da unidade, com buraco (retenção) ou mais de 500 atrás |
| Conexões | `sync.Streams` | Um envio periódico único por unidade com conexões: avisos que cada conexão pode ver, reconferência do contexto a cada 10 s, heartbeat a cada 15 s, reconexão pedida aos 30 min e no desligamento; limite de 5 conexões por contexto e 1000 no servidor |
| Equipe | `sync.web.SyncController` (`/api/v1/sync/stream`, `/api/v1/sync/changes`), `identity.LiveSessions` | Contexto = sessão + unidade escolhida + vínculo ativo; reconferência lê a sessão guardada (id, identidade, unidade, prazos de inatividade e absoluto, MFA pendente) e o vínculo, sem renovar nada |
| Consumidor | `tabs.web.GuestController` (`/api/v1/guest/tabs/{id}/stream`, `/changes`), `GuestAccess.stream` | Só com acesso válido daquela comanda no cookie; avisos só dessa comanda e só `COMANDA`/`PEDIDO`; acesso revogado ou vencido encerra |
| Sessão | `identity.web.SessionExpiryFilter` | O SSE da equipe é segundo plano pelo caminho (EventSource não manda header); polling com `X-Fastpay-Background: 1` |
| Agendador | `application.yaml` (`spring.task.scheduling.pool.size: 2`) | Job demorado (SMTP) não atrasa os avisos |
| Frontend | `live/live-updates.ts`, `live/live-status.ts`, `tabs-page`, `tab-orders-page`, `guest-tab-page` | Uma conexão por tela; ao cair, aviso "Sem conexão em tempo real" com a hora do último contato e "Atualizar agora", polling e novas tentativas com espera crescente; foco/aba visível/rede relê; reconexão relê comanda, catálogo e pedidos e confere envio sem resposta, nunca envia rascunho; rascunho de antes pede conferência; QR fecha quando o cliente confirma; trocar/perder a unidade ou sair fecha a conexão |
| Cache PWA | `especificacao-tecnica.md` §2 | Sem service worker no MVP; API sempre `no-store`; `index.html` `no-cache`; dado privado no aparelho só o rascunho por pessoa/unidade/comanda |

Nenhuma dependência nova.

## 2. Proposta aprovada por Diego em 04/11/2026 (T-03 da H17, cartão no thread da H17)

| Proposta | Valor |
|---|---|
| Procura de avisos novos no servidor | a cada 1 s (meta NFR-06: pedido na produção em até 3 s) |
| Heartbeat | a cada 15 s; o cliente considera a conexão morta com 37,5 s sem evento |
| Reconferência de sessão/acesso por conexão | a cada 10 s (logout, troca de unidade e desativação encerram em até ~11 s) |
| Duração máxima de uma conexão | 30 min, reconecta sozinha com o mesmo cursor |
| Avisos guardados para reconexão | 6 h, até 500 por reconexão; além disso, releitura completa |
| Conexões simultâneas | 5 por sessão da equipe ou acesso de consumidor; 1000 no servidor |
| Polling sem SSE | a cada 5 s, dobrando até 60 s com erro de rede |
| Nova tentativa de SSE | 2 s, dobrando até 60 s |
| Volta ao foco | relê no máximo a cada 2 s |

## 3. Testes

| Teste | Critério da H17 |
|---|---|
| `SyncIntegrationTest.avisaSoAUnidadeDoFatoEReconexaoRecebeOQuePerdeuPeloCursor` | Avisos de comanda, pedido e produção sem dados da comanda; **queda/reconexão e replay** pelo cursor (`?after=` e `Last-Event-ID`), na ordem; outra unidade não recebe nada (SSE e polling); polling igual ao SSE; `no-store` e `X-Accel-Buffering` |
| `…cursorPerdidoInvalidoOuDeOutraSequenciaPedeLeituraCompleta` | **Cursor perdido**: inválido, negativo, à frente, longo demais e com buraco da retenção pedem releitura (polling e SSE); cursor válido recebe só o que falta |
| `…semSessaoSemUnidadeOuSemVinculoNaoConectaEVinculoDesativadoEncerraAConexao` | Sem sessão 401, sem unidade 409; **usuário sem vínculo**: desativado durante a conexão, ela termina na reconferência com `UNIDADE_NAO_AUTORIZADA` e não reabre (403, depois 409) |
| `…heartbeatPollingEReconexaoNaoRenovamASessaoEASessaoVencidaEncerraAConexao` | **Sessão não renovada pelo heartbeat**, nem pelo polling, nem pela reconexão aos 30 min; **sessão expirada** (1 h do garçom) encerra a conexão com `SESSAO_ENCERRADA` e o polling recebe 401 |
| `…logoutETrocaDeUnidadeEncerramAConexaoEADuracaoMaximaPedeReconexao` | **Cancelar streams ao trocar unidade** e no logout; duração máxima pede `RECONECTAR` |
| `…consumidorRecebeSoAvisosDaPropriaComandaSemProducaoEAcessoRevogadoEncerra` | Consumidor sem acesso 404; só avisos da própria comanda, sem produção (SSE e polling); recuperação revoga o acesso e encerra com `ACESSO_ENCERRADO` |
| `…sequenciaSemBuracosComEscritaConcorrenteEAvisoDesfeitoComOFato` | 8 aberturas simultâneas geram posições 1–8 sem buraco; pedido recusado para revisão não grava aviso nem posição |
| `…limiteDeConexoesSimultaneasPorSessao` | 6ª conexão 429 `MUITAS_CONEXOES`; polling continua |
| `MigrationsIntegrationTest` (3) | V1–V14 em banco vazio e de V1 para V14 |
| `TenantIsolationIntegrationTest` | Tabelas novas com RLS forçado, runtime não é dono |
| `ModularityTest` | Módulo `sync` sem ciclo nem acesso a internos |
| Frontend `live-updates.spec.ts` (7) | Resync ao conectar, cursor seguido, **queda**: polling com o cursor e reconexão `?after=`; espera crescente 2→4→8→16→32→60→60 s; silêncio de 37,5 s derruba; `RECONECTAR` reabre sem aviso; sessão recusada para de tentar; foco/rede relê uma vez; trocar/perder a unidade fecha |
| Frontend `tab-orders-page.spec.ts` (+2) | Aviso da comanda relê pedidos/cabeçalho, de outra comanda não; queda mostra dados desatualizados, relê tudo e **não envia o rascunho**; rascunho de antes pede conferência |
| Frontend `tabs-page.spec.ts` (+1) | Aviso de comanda relê a lista; QR fecha quando o cliente confirma noutro aparelho |
| Frontend `guest-tab-page.spec.ts` (+1) | Aviso relê a comanda; acesso encerrado mostra a recusa do servidor |

## 4. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 da imagem fixada), sessão em nuvem, 04/11/2026 | **364 testes, 0 falhas** (eram 356 na H16); enforcer, Modulith e JaCoCo ok |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 04/11/2026 | lint ok; **158 testes em 30 arquivos, 0 falhas** (11 novos); build ok |

Não executados: `compose-smoke.sh` e build das imagens localmente (rodam na CI do PR); E2E Playwright (não configurado); queda real de rede num celular/tablet, proxy/tunnel de produção (H50) e teste manual na homologação de Diego.
