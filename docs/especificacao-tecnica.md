# Especificação técnica — fastPay

Versão 1.0 · 06/10/2026 · Responsável pelo produto e piloto: Diego.
Base funcional: [PRD v3.0](PRD-fastPay-atualizado.md). Bootstrap técnico (H01) iniciado em 07/10/2026; regras de negócio ainda não implementadas.

Este documento consolida as escolhas da entrevista e define contratos de implementação. Modelos de tabelas, nomes de pacotes, endpoints e variáveis abaixo são diretrizes técnicas para materializar essas escolhas, não código existente. Pendências têm gates em [decisoes-pendentes.md](decisoes-pendentes.md); não converter uma hipótese em regra aprovada.

## 1. Stack e congelamento de versões

| Componente | Base escolhida / situação | Como tornar reproduzível |
|---|---|---|
| Backend | Java 25; Spring Boot 4.1.1; Maven 3.9.x | Fixar distribuição/patch JDK, Maven Wrapper e parent/BOM no bootstrap |
| Módulos e eventos | Spring Modulith; 2.1.1 como candidato de integração | Provar compatibilidade com Boot, schema JDBC e recuperação em H01/H04; importar BOM explícito |
| Persistência | PostgreSQL; Spring Data JPA/Hibernate gerenciados pelo Boot; Flyway | Major/patch PostgreSQL ainda aberto em T-01; fixar imagem por digest; não sobrepor versões transitivas sem motivo |
| Frontend | Angular e CLI 22.0.x; TypeScript 6.0.x; RxJS 7.x compatível | Congelar patches compatíveis em package.json e package-lock.json; instalação com npm ci |
| Build frontend | Node 24.x, mínimo 24.15.0 para a linha escolhida | Fixar patch e npm; Node não é servidor da aplicação em produção |
| UI | Angular Material + CDK alinhados ao Angular | Tema fastPay e componentes acessíveis; sem segundo design system |
| Sessão | Spring Security + Spring Session JDBC | Versões do BOM; tabelas migradas pelo Flyway |
| Backend tests | JUnit Jupiter, Mockito, Testcontainers, JaCoCo, PIT | Fixar plugins compatíveis com Java 25; testes reais em H01/H03 |
| Frontend/E2E | Vitest, Angular TestBed, Playwright | Fixar pacotes e browsers do Playwright no lockfile/CI |
| Execução | Docker + Compose; Linux na VPS | Fixar imagens; distribuição Linux, proxy e versões em T-02 |
| Backup | pgBackRest + WAL para AWS S3 | Fixar versão compatível com o PostgreSQL selecionado em T-01 |
| Telemetria | Actuator, Micrometer, logs JSON, Grafana Cloud | Coletor e limites definidos antes de produção; não presumir serviço ilimitado |

As famílias aprovadas não autorizam tags `latest` nem resolução flutuante em CI. H01 deve registrar versões exatas, fontes oficiais, checksums/digests, build e testes executados. Escolher patches compatíveis é trabalho técnico autorizado; trocar linguagem/framework/major aprovado exige decisão de Diego. A matriz pública consultada do Modulith não bastou para confirmar a combinação completa: o gate de integração permanece explícito. Versões fixadas em H01: [evidencias/H01-build.md](evidencias/H01-build.md) (Temurin 25.0.4.1+1, Maven 3.9.16, Boot 4.1.1, Modulith 2.1.1, Angular 22.0.8, Node 24.18.0/npm 11.16.0).

Fontes técnicas consultadas em 06/10/2026: [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), [Angular](https://angular.dev/reference/versions), [Modulith/compatibilidade](https://docs.spring.io/spring-modulith/reference/appendix.html), [eventos Modulith](https://docs.spring.io/spring-modulith/reference/events.html), [PostgreSQL RLS](https://www.postgresql.org/docs/current/ddl-rowsecurity.html), [pgBackRest](https://pgbackrest.org/user-guide.html). Revalidar ao fixar versões; este documento não comprova build.

## 2. Arquitetura e estrutura

Monólito modular, um deploy backend, um banco e um frontend. Clean Architecture pragmática por domínio; um módulo Maven inicialmente. Contratos públicos síncronos para invariantes imediatos; eventos persistidos para efeitos recuperáveis. Não introduzir Kafka, RabbitMQ, Redis, microsserviços ou Kubernetes no MVP sem necessidade demonstrada e aprovação de mudança.

| Caminho planejado | Responsabilidade |
|---|---|
| backend/ | Maven Wrapper, aplicação Java e testes |
| backend/src/main/java/.../identity/ | Identidades, credenciais, MFA, sessões e permissões |
| .../establishments/ | Cadastro, vínculos, configuração e ativação |
| .../catalog/ | Produtos, setores, disponibilidade, imagens e referência local |
| .../tabs/ | Comandas, visitas, acesso, composição e fechamento |
| .../orders/ | Rascunho/envio, linhas e alocações de consumo |
| .../production/ | Fila, etapas, cancelamentos e entrega |
| .../service/ | Problemas, responsáveis e central |
| .../payments/ | Tentativas, recebimentos, PSP, devoluções e conciliação |
| .../billing/ | Planos, competências, comissão e ciclo comercial |
| .../reporting/ | Projeções, métricas, CSV e referência da rede |
| .../platform/ | Suporte autorizado e operações administrativas |
| frontend/src/app/ | Core mínimo, shared UI e features por área |
| docs/ | PRD, especificação, histórias, decisões, progresso, evidências e runbooks |
| infra/ | Dockerfiles, Compose, configuração de proxy e observabilidade |
| scripts/ | Verificações e rotinas operacionais documentadas |
| AGENTS.md / CLAUDE.md | Regras de execução para os agentes |

Dentro de cada módulo: `api` expõe contratos/eventos/DTOs intermodulares; `domain` guarda regras sem JPA/HTTP; `application` coordena casos de uso; `infrastructure` implementa persistência/adaptadores; `web` traduz HTTP. Criar pacotes quando usados. Outros módulos não importam entidades, repositórios ou implementações internas. Módulo compartilhado, se necessário, só primitivas realmente comuns, não regras dispersas. Spring Modulith valida fronteiras e ciclos. Uma transação pode coordenar contratos públicos de módulos para invariantes da mesma operação.

Frontend: standalone components, rotas lazy por consumidor, garçom, produção, gerente, admin estabelecimento e admin fastPay. Signals para estado local/feature; RxJS para requisições, SSE e cancelamento. Reactive Forms tipados; sem NgRx inicialmente. Guards melhoram navegação, nunca substituem autorização no backend. Limpar dados da unidade anterior ao trocar contexto. Rascunhos locais vinculados a ator/unidade/comanda; não reaproveitar entre contas. Acesso móvel prioritário em salão/consumidor, tablet em produção e desktop responsivo nos painéis.

PWA sem operação financeira offline. Cache não deve armazenar credenciais ou respostas privadas como verdade atual. A estratégia de service worker precisa excluir API/auth e tratar atualização de versão; sua implementação fica em H17/H48. Não enviar rascunhos automaticamente após reconexão.

## 3. Banco, isolamento e migrações

Banco compartilhado; registros pertencentes a estabelecimento carregam `establishment_id NOT NULL`. UUIDs opacos como IDs técnicos; número curto da comanda é identificador de exibição, único entre ativas na unidade, nunca autorização. Instantes em UTC (`timestamptz`), com fuso IANA, corte e versão da política operacional preservados. Chaves compostas/FKs com estabelecimento impedem relações cruzadas. Índices seguem consultas medidas, especialmente unidade/estado/data, referências PSP e jobs pendentes.

RLS desde o MVP. Contexto de unidade vem da identidade e vínculo validados, jamais de header confiado isoladamente. Definir contexto com escopo da transação (`SET LOCAL`/equivalente parametrizado), limpar ao concluir; testar reuso de conexão e rollback. Ausência de contexto nega dados. Papel runtime não é owner, superuser nem BYPASSRLS; habilitar/forçar RLS onde aplicável. Migração tem credencial separada e não fica no processo web em produção.

Identidades globais, sessões, registro de unidades, jobs e agregados da rede não seguem indiscriminadamente a mesma política tenant. Desenhar políticas específicas e acesso mínimo. Admin fastPay não ganha bypass geral para comandas. Suporte usa concessão limitada ao estabelecimento, prazo e leitura. Jobs globais elegem trabalho por metadados mínimos, estabelecem contexto restrito e executam unidade a unidade. Consolidação de dono consulta apenas vínculos atuais. Agregados de rede são produzidos por rotina restrita, sem expor tabelas transacionais à interface administrativa.

Flyway é a única fonte de schema, incluindo RLS, índices, tabelas de sessões e eventos. Desativar criação automática dessas tabelas pelos frameworks em produção; Hibernate `ddl-auto=validate`. Não alterar migration aplicada; nova versão corrige. Mudanças destrutivas exigem plano, backup e autorização específica quando colocarem dados em risco. Preferir expandir→migrar→contrair para permitir rollback de aplicação. Restauração de backup não é rollback rotineiro: pode perder operações e exige conciliação.

### 3.1 Modelo lógico mínimo

| Conjunto | Entidades e invariantes principais |
|---|---|
| Identidade | user, credential, mfa, session, membership, role/permission, invitation, recovery_token; vínculo separado da identidade global |
| Estabelecimento | establishment, configuration_version, sector, operational_calendar; estado operacional distinto de faturamento e PSP |
| Catálogo | product, product_media, network_reference_mapping; preço capturado no envio |
| Atendimento | visit, tab, guest_grant, binding_token, problem, assignment; grant revogável por comanda |
| Pedido | order, order_line, tab_allocation, allocation_movement; quantidade inteira, preço e destino imutáveis no envio |
| Produção | production_line/event, cancellation_ack, delivery_event; uma sequência por linha de origem |
| Financeiro | bill_snapshot, payment_attempt, external_receipt, refund/reservation/component, reconciliation_case, exit_authorization, debt |
| Comercial | plan_version, establishment_contract, billing_cycle, monthly_charge, commission_entry/reversal |
| Infra/dados | audit_event, idempotency_record, webhook_inbox, event_publication, job_attempt, file_object, export_job, report_projection |

IDs e estrutura física devem ser finalizados nas histórias, com constraints e migration. Evitar apagar fatos pagos/entregues. Auditoria append-only no caminho da aplicação; alterações de negócio produzem novos eventos/ajustes. Retenção/anonimização controladas por política P-08, não por exclusão em cascata genérica.

### 3.2 Quantidade, transferência e produção

Uma linha enviada com quantidade Q tem uma sequência de produção. Alocações distribuem unidades inteiras em comandas; soma das quantidades ativas mais canceladas permanece Q. Transferência divide alocação, preserva origem/preço/etapas e não recria produção. Fechamento de cada comanda depende das unidades ativas ali alocadas estarem entregues ou canceladas.

Entrega marca todas as unidades remanescentes da linha, com lista de destinos atualizada. Sem entrega parcial de uma linha: o garçom só confirma quando todos os destinos foram atendidos. Precisa entregar separadamente? Criar linhas distintas antes do envio. Se destinos mudaram enquanto tela estava aberta, conflito exige atualização/reconfirmação. Cancelamento parcial reduz remanescente, preserva original/cancelado; total encerra linha. Ciência da cozinha é independente do ajuste financeiro.

Desfazer entrega: gerente ou permissão específica delegada, motivo obrigatório; todas as comandas com alocações ativas precisam estar abertas e sem fechamento/cobrança. Volta a pronto com histórico. Comanda paga/encerrada gera ocorrência operacional; não reabrir nem devolver automaticamente. Métrica usa a última entrega válida; invalidar amostra errônea sem apagar evento.

## 4. Transações, concorrência e idempotência

`@Version` nas entidades concorrentes. Locks curtos em envio/fechamento, transferência entre comandas e reserva de devolução; ordenar IDs para reduzir deadlocks. Revalidar permissão, estado, versão e saldo na transação. Nenhuma chamada de rede ao PSP sob lock do banco. Commit de negócio e publicação durável de evento juntos. Após timeout, consultar operação pelo identificador antes de criar outra.

Comandos críticos recebem chave de idempotência e versão esperada. Chave escopada por ator/contexto/operação; hash do payload impede reutilizar a mesma chave com conteúdo diferente (409). Persistir resultado e referências; constraints exclusivas para competência mensal, referência de pagamento por conta PSP, evento recebido e operação de envio. Idempotência financeira é reforçada por invariantes permanentes; expirar cache de respostas não autoriza nova cobrança. Prazo dos registros auxiliares depende de retenção, P-08.

| Operação | Unidade de consistência / comportamento |
|---|---|
| Envio | Pedido inteiro; preço mudou exige reconfirmação, indisponibilidade mantém tudo no rascunho |
| Fechamento | Serializa com envio/cancelamento/transferência; consolida versão e total |
| Transferência | Origem+destino+alocações atômicos, ambas abertas na mesma unidade |
| Produção em lote | Resultado individual por linha; até 50, sucesso parcial explícito; estado/versão esperados evitam avançar duas vezes |
| Devolução | Reserva atômica global e por componente; resultado incerto mantém reserva |
| Cobrança PSP | Persistir intenção, chamar com idempotência externa, conciliar; timeout não significa recusa |
| Efeitos assíncronos | Entrega ao menos uma vez; consumidor deduplica por evento/efeito; nenhuma promessa de exactly-once externo |

Problema aberto de item bloqueia transferência daquele item; problema de conta bloqueia entradas e saídas da comanda. Correções autorizadas continuam possíveis conforme estado/reabertura; corrigir valor não conclui chamado automaticamente. Desconto que excede autoridade após redução de consumo marca revisão e bloqueia cobrança, sem impedir cancelamento/transferência legítimos.

## 5. Autenticação, sessão e autorização

Mesma origem frontend/API; cookies de sessão HttpOnly, Secure, SameSite=Lax em HTTPS. Spring Session JDBC mantém sessão server-side; proteção CSRF integrada ao Angular em mutações, inclusive rotas autenticadas. OAuth e webhook têm proteção própria (state/nonce/assinatura e validações) e exceções pontuais, nunca desabilitar CSRF globalmente. Sem tokens de sessão em localStorage ou URLs/logs. Rotacionar sessão após login/privilégio, proteger contra enumeração e limitar tentativas. Algoritmo/custo de senha e limites quantitativos entram em P-07/T-03.

| Perfil | Inatividade | Duração absoluta |
|---|---:|---:|
| Garçom / produção | 1 hora | 12 horas |
| Gerente / admin estabelecimento | 30 minutos | 12 horas |
| Admin fastPay | 15 minutos | 8 horas |
| Consumidor com Google | 7 dias | 30 dias |

Adotar limite mais restritivo no contexto privilegiado acessível pela sessão; troca de área não estende início absoluto. Avisar antes de expirar e revalidar rascunhos após reautenticar. SSE, polling e heartbeats não contam como atividade humana. Logout/desativação revogam acessos e streams correspondentes. Revogar vínculo de uma unidade não apaga outros vínculos.

Avulso: grant opaco persistente no mesmo navegador, separado da sessão autenticada; válido durante atendimento e 7 dias após resolução final. Débito/pagamento incerto/devolução/problema suspendem expiração temporal. Uma vinculação ativa por comanda; recuperação revoga grant anterior. Login Google sozinho não concede acesso: vinculação explícita exige grant válido. Prova presencial para recuperar e fusão de identidades não estão definidas (P-07/P-13); não usar nome/mesa/e-mail como prova suficiente.

QR de vínculo: 2 minutos, uso único; novo QR invalida anterior. GET/preview não consome; confirmação de vínculo atômica faz consumo. Token aleatório armazenado por hash, sem PII no QR. QR de saída é outro fluxo, sempre valida situação atual no servidor.

Convites: 48 h; recuperação de senha: 30 min. Único uso, novo token do mesmo propósito invalida anterior. Conta já existente autentica para aceitar vínculo. Reset revoga sessões, não MFA. Segredo TOTP cifrado com chave fora do banco; códigos de recuperação por hash. TOTP obrigatório para gerente/admin estabelecimento/admin fastPay, opcional demais. Dez códigos de recuperação aleatórios, uso único, hash, exibidos apenas na geração. Senha+código abre fluxo restrito de recadastro de MFA; conclusão invalida autenticador/códigos antigos e sessões, gera novos e notifica. Sem códigos, recuperação humana forte, inclusive único admin fastPay, precisa P-07; e-mail sozinho não remove MFA.

Permissões explícitas por ação, não pelo nome da rota. Revalidar vínculo vigente em cada comando e download. Atendimento de problemas e desfazer entrega podem ser delegados; devolução, débito e invalidação externa são gerente, não permissão delegável ao garçom. Admin loja não desativa MFA de identidade global alheia. Nunca permitir remover o último admin ativo sem substituição.

## 6. Contratos HTTP e tempo real

REST `/api/v1`; JSON de DTOs versionados, validação backend, OpenAPI versionada em CI. Lista paginada até 100 registros, ordenação determinística com ID como desempate. Erros `application/problem+json` (RFC 9457): status, código estável, mensagem segura, erros de campo e correlationId; sem stacktrace. 401 sessão, 403 permissão, 409 versão/estado conflitante, 422 regra de negócio quando aplicável e 429 limite. IDs de terceiros não devem revelar existência por diferenças desnecessárias na resposta.

Famílias de recursos planejadas: `/establishments`, `/memberships`, `/catalog`, `/tabs`, `/orders`, `/production`, `/problems`, `/payments`, `/refunds`, `/billing`, `/reports`, `/exports` e `/files`. Ações como fechar, transferir e autorizar devolução são comandos explícitos; não oferecer PATCH genérico que contorne invariantes. Contratos concretos nas histórias respectivas.

SSE comunica mudança/versão e força atualização autorizada, não substitui leitura canônica. Filtrar por usuário/unidade/comanda; nenhum broadcast multi-tenant. Revalidar/revogar streams, heartbeat não prolonga sessão. Após reconnect/foco, ressincronizar estado; cursor expirado faz snapshot completo. Polling com backoff como fallback, sem sobrecarga. Proxy sem buffering e com timeout/keepalive apropriados, validados em H17/H50. Consumidor não recebe dados financeiros de terceiros ou mensagens de produção internas.

## 7. Dinheiro e calendário

BRL no MVP. BigDecimal e NUMERIC(19,2) para totais monetários; JSON usa string decimal, nunca double para domínio. Percentuais até 4 casas (10.0000 representa 10%). Quantidades inteiras positivas até 99 por linha de envio. Preço digitado com mais de 2 casas é rejeitado, não silenciosamente ajustado. Limites máximos monetários e taxa/quotas antiabuso: T-03; capacidade de coluna não é limite de negócio.

HALF_UP em 2 casas: calcular desconto D, arredondar; formar base do serviço com D arredondado quando configurado; calcular/arredondar S; T=C−D+K+S. Nunca total negativo. Desconto fixo efetivo=min(valor concedido,C); se novo percentual efetivo superar autoridade original, precisa revisão por autorizado antes da cobrança. Gerente limita ao consumo. Snapshot de versão/preço/config/desconto e autorização acompanha a cobrança.

Comissão por pagamento integrado aprovado, base efetivamente paga incluindo serviço/couvert após desconto. Reversão parcial acumulada = round(comissãoOriginal × devolvidoConfirmadoAcumulado / pagoOriginal) − reversõesAnteriores. No total, exatamente comissão original. Não recalcular por plano atual. Tarifas/chargeback dependem contrato, P-01/P-04.

Exemplo de teste: C=100.00, D=10.00, K=5.00 e serviço 10% após desconto sem couvert → S=9.00, total 104.00. Desconto analítico de 0.01 entre três linhas iguais atribui centavo ao menor ID estável, com soma exata. Comissão original 0.01 em pagamento 1.00 devolvido em 0.33/0.33/0.34 precisa terminar com reversão 0.01.

Dia operacional local guarda fuso e corte da época. Dono consolidando lojas com diferentes fusos/cortes vê os intervalos de cada uma. Rede usa fuso de referência configurável, inicialmente America/Sao_Paulo, dias civis 00 h–00 h; não misturar com período local sem rótulo. Demanda usa envio; recebimento usa aprovação efetiva; devolução usa confirmação. Projeções guardam origem e processamento de evento tardio.

## 8. PSP, pagamento e devolução

Mercado Pago é candidato, não integração já comprovada. H05/H06 validam OAuth/recebedor, Pix/cartão/split, comissão, refund total/parcial, insuficiência de saldo, revogação, consulta, eventos repetidos/tardios, chargeback, relatórios e mensalidade fastPay. Falta de acesso ou sandbox insuficiente deixa evidência pendente; mock não comprova suporte comercial. Não usar API/endpoints presumidos: implementar pelo contrato validado.

Adaptador expõe criar/consultar/cancelar quando suportado/devolver e normalizar eventos, preservando identificadores e conta recebedora da época. Credenciais cifradas, chave fora do banco/backup, rotação documentada. Somente admin estabelecimento conecta/substitui com reautenticação; plataforma não desvia recebedor. Cobranças antigas mantêm vínculo histórico. Credencial revogada bloqueia novos integrados; externos só se não há tentativa pendente/incerta. Acompanhar refunds antigos até reconectar/regularizar.

Webhook: endpoint público específico, validação de autenticidade conforme PSP, corpo mínimo persistido/dedup, resposta rápida e processamento recuperável. Validar transação, conta, valor e BRL; notificações insuficientes exigem consulta. Não confiar em redirect do browser como confirmação. Não armazenar PAN/CVV; usar fluxo/tokenização oficial do PSP.

Conciliação: webhook imediato; primeira consulta após 15 s de pendência, progressão até intervalo máximo de 5 min, alerta aos 15 min sem desistir; variação ajustada a rate limits comprovados. Varredura diária de operações recentes e todas as pendências antigas. Janela de recentes depende cobertura PSP em P-01. Consulta manual autorizada, dedup e rate-limit; não altera situação por declaração.

Antes de chamada externa, intenção local durável. Se resposta se perde, manter em verificação e consultar mesma referência. Incerto bloqueia outra cobrança, externo, reabertura e saída. Reprocessar mesma intenção, não criar outra por timeout. Mesmo evento deduplica; dois pagamentos distintos geram excedente para revisão gerente e devolução, não segunda venda.

Devolução: gerente autoriza, motivo, pagamento original e reserva. Pode atribuir itens/serviço/couvert ou ser valor livre com justificativa. Atribuição respeita saldo líquido de desconto e reservas anteriores do componente E saldo global. Valor livre reduz global sem inventar item; interfaces mostram valor não alocado. Pending/uncertain mantém reserva; falha definitiva libera com auditoria. Confirmado integrado depende PSP; externo exige registro/comprovação do ato real. Não reabrir consumo nem apagar entrega. Motivo de couvert: indevido/isenção conserva dispensa na visita; duplicado preserva cobrança válida; correção exige inclusão explícita correta.

Conta zerada encerra sem pagamento/PSP/comissão. Externo pode ser invalidado por gerente sem movimentar dinheiro e restaura pendência; devolução real é outro processo. Débito regularizado recebe integralmente no dia atual, sem reabrir consumo. Saída usa estado canônico, preserva validações históricas mesmo após ajustes posteriores.

## 9. Eventos, jobs e e-mail

Spring Modulith com registry persistido no PostgreSQL e mesma transação do fato. Schema Flyway; validar a versão escolhida. Listener idempotente com efeitos deduplicados; não depender só de evento em memória nem `@Async` isolado. Worker identifica contexto tenant do evento e restaura autorização adequada. Claim/lease de job evita execução concorrente; falha/restart recupera trabalho abandonado. Não transportar segredos em eventos.

| Job | Gatilho / repetição | Critério de fim |
|---|---|---|
| Encaminhar produção | Commit do envio | Linha visível uma vez por origem |
| E-mail | Convite/reset/avisos | Aceite pelo provedor + status de entrega separado quando disponível |
| Projeções/SSE | Eventos; atualização periódica | Versão/data de referência publicadas |
| Exportação | Solicitação autenticada | Arquivo ou falha visível; acesso 24 h |
| Reconciliação | Pendência + rotina diária | Situação definitiva comprovada, não simples número de tentativas |
| Cobrança mensal | Competência única vencendo | Mesmo ciclo não cria segunda cobrança |
| Limpeza | Tokens/exports expirados; retenção aprovada | Remoção controlada, preservando evidências necessárias |

Não financeiros: até 5 tentativas totais, contando primeira, backoff progressivo configurável; ao esgotar, pendência/alerta e retry autorizado da mesma operação. Financeiros incertos continuam reconciliação, mesmo além de 5. Reprocessamento exige validade atual e permissão equivalente à ação original. Suporte técnico não ganha poderes financeiros. Registrar tentativas, próxima execução, último erro sanitizado e correlationId.

Produção: SES com domínio/remetente verificados, acesso produtivo e tratamento de bounce/complaint. Homologação: Gmail SMTP dedicado, senha de app se a conta permitir, secrets; não prometer limites de envio. Desenvolvimento: caixa de teste sem destinatários reais. Configurar lista de destinatários permitidos e identificação de ambiente antes de HML compartilhar dados; não enviar mensagens automaticamente agora.

## 10. Arquivos, documentos e exportação

StoragePort: local em dev/HML, S3 privado em produção. Bucket de arquivos separado de backups. Objetos com IDs aleatórios e metadados unidade/finalidade/tamanho/checksum/autor. Autorização no backend para upload/download, sem bucket público; logo pode ser entregue na página autorizada da comanda. Não confiar em caminho ou Content-Type do usuário. Validar assinatura, decodificar imagem, limitar dimensões/descompressão, reprocessar mídia exibida e não executar PDF. Política de inspeção/quarentena T-04 antes de upload produtivo.

| Tipo | Quantidade / tamanho | Formatos / tratamento |
|---|---|---|
| Foto produto | 1 por produto, até 5 MB | JPEG/PNG/WebP; otimizar imagem e remover metadados desnecessários |
| Logo estabelecimento | 1 opcional, até 5 MB | JPEG/PNG/WebP; substituir/remover, fallback nome/iniciais |
| Comprovação devolução externa | Até3 por devolução,10 MB cada | JPEG/PNG/PDF; preservar original/evidência e acesso restrito |
| Comprovante não fiscal | Gerado por versão | PDF com situação atual, valores, pagamento/refund; versão histórica rastreável |
| Exportação | Até 90 dias por pedido | CSV seguro contra fórmulas, encoding/documentação e filtros auditados |

Uma exportação ativa por usuário/unidade; pedido repetido deduplica. Para relatório global, escopo explícito separado. Processamento assíncrono com filtros, instante de corte dos dados, pronto/falhou, disponibilidade 24 h. Revalidar permissão ao baixar; expiração exclui artefato, não fatos. Não criar links de 24 h que continuem válidos após revogação: servir via autorização atual. Limites de linhas/tamanho e exclusão segura em T-03/P-08.

## 11. Relatórios e inteligência

Projeções eventualmente consistentes, atualização alvo até 1 min e `dataAsOf` visível; ao falhar, último resultado com aviso de desatualização. Pagamento/fechamento/saída sempre leem origem atual, nunca projeção atrasada. Última conciliação é indicador separado de atualização do dashboard.

Dicionário: consumo bruto, desconto, couvert, serviço, total, pago integrado, externo, débito, devolvido, comissão, liquidado e disponível são medidas distintas. Desconto distribui proporcionalmente às linhas elegíveis, centavos pelo maior resto, desempate ID estável; snapshot pago imutável. Devolução atribuída ajusta componente; livre fica não alocada. Não reduzir quantidade física automaticamente por devolução monetária.

Preparo usa envio→pronto só em etapas concluídas; entrega pronto→entregue só em entregues; total envio→entregue. Pendentes têm idade atual separada. Cancelados exibem estágio/motivo/duração própria; um cancelado depois de pronto preserva amostra de preparo, sem inventar entrega. Sem preparo não entra média/p95 de preparo; medir seu envio→entrega separadamente. Uma amostra por linha de produção, não por unidade; transferência não duplica. Desfazer entrega invalida amostra anterior, conservando histórico.

Dono consolida apenas unidades autorizadas, não média simples de médias/p95. Rede tem catálogo de referência opcional, marca/apresentação, cobertura e não classificados. Classificação futura não pode reescrever histórico silenciosamente: política de reclassificação em P-11. Exportação a parceiros fica bloqueada até mínimo de lojas/concentração/recortes P-09; pequeno piloto não justifica relaxar proteção.

## 12. Ambientes, domínios e implantação

| Ambiente | Execução / dados | Serviços |
|---|---|---|
| Desenvolvimento | Local Docker, dados sintéticos | Storage local, caixa e PSP de teste |
| Homologação | Máquina pessoal Windows + Docker Desktop/WSL2 | Imagens da release, Gmail de teste, storage local, callback HTTPS por tunnel |
| Produção/piloto | Hostinger VPS: referência 2 vCPU/8 GB/100 GB NVMe | Compose, PostgreSQL, S3, SES, PSP produtivo e backups contínuos |
| Futuro | AWS | Migração planejada; sem implementar infraestrutura AWS completa agora |

Node compila frontend em CI; imagem frontend serve arquivos estáticos; backend em imagem própria. Proxy TLS roteia mesma origem e `/api`. Banco apenas rede interna, volume persistente. Build fora da VPS, limites memória/CPU definidos por teste; uma VPS é ponto único de falha, capacidade não comprovada pelo tamanho nominal. Storage/PSP/email via interfaces e env para migração futura.

GitHub privado + Actions + GHCR privado. Imagem etiquetada por commit/release e digest; não publicar secrets em camadas. Compose/config deve referenciar versão fixa. CI build/test/scan e publicação conforme política; deploy manual por versão aprovada, sem auto deploy em merge. Implantação: validar configuração/espaço/backup→migration com credencial própria→subir versão→health/prontidão→verificação operacional inicial→registrar evidência. Falha: rollback de imagem apenas com schema compatível; se não, correção planejada/restore com reconciliação.

DNS hoje na Hostinger; aprovado migrar gestão DNS para Cloudflare para o tunnel, mantendo registro/hospedagem onde contratado. Não executar migração sem plano e autorização operacional. Domínio configurável `malyah.tech`, produção `https://fastpay.malyah.tech`, callbacks HML `https://hooks-hml-fastpay.malyah.tech`. Tunnel expõe apenas callbacks necessários, não banco/Actuator/painéis internos. URLs Google/PSP, remetentes SES, cookies e TLS precisam ser revisados quando trocar domínio. Planejar antes de expirar; registrar riscos de QR/links antigos e período de transição.

Variáveis conceituais: APP_ENV, APP_PUBLIC_URL, API_BASE_PATH, HML_CALLBACK_BASE_URL, JDBC_URL, SESSION_*, GOOGLE_*, PSP_*, MAIL_*, STORAGE_*, S3_*, BACKUP_*, OBSERVABILITY_*. H01/H50 criam `.env.example` com nomes efetivos, valores fictícios e obrigatoriedade. Secrets ficam fora do Git, com acesso mínimo, rotação e backup de chaves separado. Produção não reutiliza credenciais de HML. Configuração por unidade fica no banco versionada; configuração técnica global não vira controle comercial de loja.

## 13. Backup, recuperação e operação

Produção, incluindo piloto: pgBackRest, full semanal, incremental diário, WAL contínuo para S3 externo à VPS; criptografia e chave disponível fora da VPS. Recuperação a qualquer ponto dos últimos 30 dias exige manter full e incrementais/WAL que sustentam a janela, não deletar cegamente por idade. RPO≤5 min/RTO≤2 h só aceitos após prova cronometrada. Monitorar sucesso, atraso de WAL, falta de espaço e disponibilidade do repositório.

HML: backups pontuais antes de testes destrutivos; não rotina contínua. Mesmo assim ensaio de PITR representativo antes de produção é obrigatório. Restore mensal e antes do piloto em ambiente isolado, sem disparar e-mails/webhooks/jobs produtivos. Validar dados e integridade, reter evidência sanitizada. Chaves/credenciais e objetos S3 precisam plano de recuperação próprio: backup do PostgreSQL não recupera arquivo excluído. Versionamento/ciclo de vida de arquivos em T-04/P-08.

Após restore, conciliar PSP, confirmar pagamentos/refunds e revisar registros externos/pedidos do intervalo antes de liberar novas tentativas. RPO de desastre não autoriza perda silenciosa no uso normal. Runbook inclui responsáveis, acesso emergencial, tempo e reconciliação; região S3/custo/contas em T-02 antes de provisionar.

Actuator/Micrometer, logs estruturados JSON, correlationId e eventos de auditoria separados. Métricas de latência/erro/tráfego, CPU/memória/disco, conexõesDB/locks, jobs/filas, PSP pendente/incerto, falhas de e-mail, backup/WAL e TLS. Não registrar dados de cartão/tokens/senhas/corpos pessoais desnecessários. Grafana Cloud inicialmente free; retenção observada 14 dias para logs/métricas, rever limites vigentes e volume; não definir retenção financeira por isso. Endpoint de métricas protegido. Alertas e teste externo de disponibilidade devem funcionar se a aplicação cair; encaminhar ao e-mail de Diego a configurar. Sem assumir suporte 24 h.

## 14. Testes e critérios de qualidade

JUnit/Mockito no domínio; Testcontainers PostgreSQL real para migrations, constraints, RLS, transações e pooling. Testar runtime sem privilégios administrativos: superuser passando teste não prova RLS. Modulith detecta dependências proibidas e testa módulos. Cobertura JaCoCo mínima 80%linhas/70%branches em domain/application; sem testes que apenas repetem implementação. PIT em regras críticas alteradas; threshold calibrado e registrado no primeiro uso, sem simular execução. Frontend TestBed/Vitest em interações/validação/acessibilidade, cobertura reportada sem percentual arbitrário. Playwright para jornadas críticas.

Cenários obrigatórios: IDs de outra unidade, stream/download sem permissão, QR simultâneo/expirado, revogação ativa, envio vs fechamento, transferência concorrente, lote com conflito parcial, cancelamento parcial, split de quantidade sem duplicar produção, entrega desfeita, arredondamento em centavos, zero elegível, payment timeout, webhook perdido/repetido/fora de ordem, dois pagamentos distintos, refund em paralelo/incerto, retry após restart e restauração. PSP fake auxilia testes locais; gate do provedor exige ambiente/evidência real apropriada.

CI por PR: backend verify, testes frontend/lint/build, contratos/módulos, E2E aplicável, varredura de segredos/dependências e artefatos de relatório. Segredos produtivos ausentes em PR; não executar código de fork com credenciais. Registrar comando, commit, ambiente, resultado e evidência. A indisponibilidade de Docker/PSP/credencial é bloqueio real, não teste aprovado.

Carga:3 unidades ×250 comandas abertas ×30 tentativas de pagamento/min/unidade junto ao mix operacional. Abrir/consultar/enviar p95≤2 s; produção≤3 s; PSP recebido→UI≤5 s; dashboard≤5 s e frescor≤1 min. P-12 define percentis/janelas das metas ainda não especificadas e mix; medir correção/isolamento além da latência. Revalidar15 unidades antes de expansão.

## 15. Desenvolvimento e liberação

Git Flow com develop. Feature→PR develop; release estabiliza e vai main, sincronizando develop; hotfix nasce main e volta às duas. Main representa estável; merge não implica deploy. Uma história por vez, Codex ou Claude Code; regras comuns em [AGENTS.md](../AGENTS.md). Branch/commit/push/PR autorizados; merge e deploy somente por solicitação explícita.

Piloto depende do fluxo completo, permissões/isolation, cenários financeiros extremos, restore medido, alertas funcionando, orientação de contingência/suporte e gates cadastrais/comerciais/privacidade resolvidos. Diego valida e libera. Cobrança errada, pedido perdido ou acesso indevido bloqueiam; ajustes visuais menores podem ser aceitos com registro. Fiscal é externo no MVP, emissão no fastPay explicitamente fase 2. Não há feature/cota de IA neste escopo.
