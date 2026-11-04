# H18 — Evidência da fila de produção e entrega por linha

Registro sanitizado. Claude Code, 04/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V15__producao_etapas_e_entrega.sql` | `production_line` com as quatro etapas, o instante de cada uma e a impossibilidade (motivo, quando, quem); `CHECK`s: sem setor só pronta/entregue, etapa com o instante dela, impossibilidade só antes do pronto. `UPDATE` do runtime só nas colunas das etapas e com identidade no contexto. `production_event` append-only (ação, de/para, motivo, versão, quem, quando). `sector.delay_limit_minutes` (1–240, padrão 15) |
| Regras | `production.domain.ProductionRules` | Sequência sem pular etapa; "não é possível atender" só com setor e antes do pronto, bloqueia todo avanço até retirar; motivo normalizado; atraso desde o envio até a entrega |
| Serviço | `production.Production` | Fila (`OPERAR_PRODUCAO`) e entrega (`OPERAR_COMANDA`), cada ação com a versão lida e a linha travada; histórico e aviso `PRODUCAO` para cada comanda da linha na mesma transação; leitura de pedido, comanda, setor e nome pelas APIs públicas dos módulos |
| API | `production.web.ProductionController` | `GET /api/v1/production/queue?sectorId=`, `GET /deliveries`, `POST /lines/{id}/start`, `/ready`, `/impossible`, `/impossible/withdraw`, `/deliver` |
| Catálogo | `Catalog.createSector/updateSector`, `CatalogController` | Limite de atraso do setor no `POST`/`PUT` (sem o campo: padrão na criação, mantém na edição); auditoria `setor.atraso_alterado` |
| Frontend | `production/production-page.ts` (`/producao`), `production/deliveries-page.ts` (`/entregas`), `catalog-page.ts`, `unit-home-page.ts` | Fila por setor com destinos, responsável, observação, tempos, destaque de atraso e de item novo; iniciar, pronto, "Não é possível atender" com motivo, retirar. Entregas em três listas: impossibilidades, prontos com preparo, sem preparo; entregar a linha inteira; versão mudada relê e pede nova confirmação. Ambas relidas pelos avisos da H17 |

Nenhuma dependência nova.

## 2. Proposta aprovada por Diego em 04/11/2026 (T-03 da H18, cartão no thread da H18)

| Proposta | Valor |
|---|---|
| Limite de atraso por setor | 1 a 240 min, padrão 15 (setor novo e existentes), editável no catálogo; só destaque, desde o envio até a entrega; sem setor não tem limite |
| Motivo de "Não é possível atender" | 5 a 300 caracteres |
| Retirar a sinalização | A produção pode retirar (insumo chegou, engano), com histórico; senão, só o cancelamento (H23) resolve |
| Sequência | Sem pular etapa: pronto exige "em preparo" antes (lote na H19) |
| Responsável na fila | Quem abriu a comanda, até a H20 definir o responsável de atendimento |
| Linhas por leitura | 200, das mais antigas às mais novas |

## 3. Testes

| Teste | Critério da H18 |
|---|---|
| `ProductionIntegrationTest.filaPorSetorComRelogiosDeEtapaEEntregaDaLinhaInteira` | Fila por setor e de todos, sem valores; **linha única para quantidade 3**; destinos, responsável, observação; **relógios de etapa** (`startedAt`, `statusSince`, `readyAt`, `deliveredAt`) e atraso pelo limite do setor; mesma versão duas vezes recusada; sem preparo nasce pronta e aparece na entrega; **entrega com versão desatualizada** (contrato H23/H25) recusada e depois aceita; histórico com quem fez cada etapa; avisos `PRODUCAO` |
| `…impossivelTemMotivoBloqueiaOAvancoEApareceParaQuemAtende` | Motivo obrigatório e normalizado; **impossível não avança** (aguardando e em preparo); aparece na entrega; retirar volta a avançar; sem setor não sinaliza; o banco recusa impossibilidade depois do pronto e etapa sem instante |
| `…permissoesPorPapelEUnidade` | **Permissões**: garçom não opera a fila, produção não entrega; outra unidade não vê nem mexe (404); administrador faz as duas partes; versão ausente 400 |
| `…entregasSimultaneasDaMesmaLinhaSoUmaPassa` | 4 entregas simultâneas: uma 200, as outras 409, um evento de entrega |
| `…limiteDeAtrasoDoSetorPeloCatalogo` | Padrão 15, limites 1–240, edição mantém o atual, auditoria |
| `ProductionRulesTest` (4) | Sequência, impossibilidade, motivo nos limites exatos, atraso |
| `MigrationsIntegrationTest` (3) | V1–V15 em banco vazio e de V1 para V15 |
| `TenantIsolationIntegrationTest`, `ModularityTest` | Tabela nova com RLS forçado e runtime sem posse; módulo `production` sem ciclo |
| Frontend `production-page.spec.ts` (7) | Linha com destinos/observação/responsável/tempos sem valores; destaque de atraso; iniciar/pronto com versão e conflito relendo; motivo e retirada; filtro de setor e releitura pelo aviso; sinal de item novo; recusa por papel |
| Frontend `deliveries-page.spec.ts` (3) | Três listas e destinos múltiplos; entrega com versão, destinos mudados relidos e reconfirmados; aviso relê |
| Frontend `catalog-page.spec.ts`, `unit-home-page.spec.ts` | Atraso do setor (validação e corpo); atalhos Produção/Entregas por permissão |

## 4. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B clean verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL da imagem fixada), sessão em nuvem, 04/11/2026 | **373 testes, 0 falhas** (eram 364 na H17); enforcer, Modulith e JaCoCo ok |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 04/11/2026 | lint ok; **169 testes em 32 arquivos, 0 falhas** (eram 158 na H17); build ok |

Não executados: `compose-smoke.sh` e build das imagens localmente (rodam na CI do PR); E2E Playwright (não configurado); uso real num tablet de cozinha e teste manual na homologação de Diego.
