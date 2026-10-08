# Decisões e pendências — fastPay

Versão 1.0 · 06/10/2026 · Responsável de produto: Diego.
Registro consolidado da entrevista; a data é de consolidação, não uma data inventada para cada aprovação. IDs P-01–P-15 preservam rastreabilidade com o PRD. Histórias estão em [epicos-e-historias.md](epicos-e-historias.md).

## Como usar

Uma pendência bloqueia apenas o comportamento que depende dela e o gate explicitado. Não parar todo o projeto por acesso PSP ausente: concluir ou registrar a história atual e escolher depois outra elegível. Não marcar como resolvida por haver um mock ou uma sugestão. Registro de encerramento exige decisão, aprovador, data real, justificativa/evidência e documentos/histórias atualizados.

Estados: aberta; parcialmente resolvida; resolvida; não aplicável. Uma decisão externa pode exigir participação do PSP, contabilidade ou privacidade; Diego coordena esses responsáveis, sem atribuir pareceres a quem não os forneceu. Política com efeito de cobrança/identidade/privacidade não pode ser inventada pelo agente.

## 1. Decisões já fechadas

| ID | Decisão consolidada | Efeito e referência |
|---|---|---|
| D-01 | Comanda individual aberta por funcionário, QR na tela, avulso ou Google opcional | Sem abertura/autopedido pelo consumidor; FR-07–11 |
| D-02 | Encerrar valor zero sem autorização/PSP/comissão | Só quando elegível e sem pendências; FR-41, H29 |
| D-03 | Preço capturado no envio | Não abertura; mudança no rascunho exige confirmação; FR-16 |
| D-04 | Envio atômico; lote de produção com sucesso parcial por linha | Resultado individual e versão esperada; H16/H19 |
| D-05 | Transferir quantidade inteira parcial ou total de uma linha | Revoga proibição v2; não fracionar valor de uma unidade; produção única; H25 |
| D-06 | Cancelamento parcial de quantidade; entrega conjunta de toda linha | Preservar original/cancelado/restante; sem entrega parcial; H18/H23 |
| D-07 | Desfazer entrega por gerente/permissão específica com motivo | Só com todas as alocações ativas em comandas abertas e sem cobrança; H24 |
| D-08 | Serviço snapshot abertura; couvert auto abertura/manual inclusão; modalidade atendimento/saída só novas comandas | Permissões valem imediatamente; H14/H20/H32 |
| D-09 | Desconto fixo efetivo limitado ao consumo; revisão se exceder autoridade após redução | Correção completa, cobrança bloqueada até revisar; H22 |
| D-10 | HALF_UP2; comissão original e reversão proporcional acumulada | Sem double e centavos residuais; H22/H40 |
| D-11 | Refund atribuído a item/serviço/couvert ou livre justificado | Reservas por componente e global; livre sem distribuição fictícia; H35/H36 |
| D-12 | Couvert devolvido por indevido/isenção mantém dispensa da visita | Duplicidade preserva válido, correção explícita; H26 |
| D-13 | Limites:50 linhas/envio,99 unidades/linha,50 produção/lote,100 por página,90 dias/export | Configuração técnica, não cota de plano; H16/H19/H45 |
| D-14 | Exportação assíncrona,1 ativa por usuário/unidade, acesso 24 h | Permissão revalidada no download; H45 |
| D-15 | Dashboard até 1 min de atualização, último dado visível | Pagamento/saída usam origem; H42 |
| D-16 | Uma amostra de tempo por linha; sem preparo fora da média de preparo | Cancelamentos e pendentes separados; H43 |
| D-17 | QR2 min; convites 48 h; reset 30 min; sessões e MFA conforme especificação | Prova excepcional de identidade ainda aberta; H08/H09/H15 |
| D-18 | 10 códigos MFA únicos; recuperação com senha+código e recadastro restrito | Não remover MFA por reset de senha; H09 |
| D-19 | Cadastro/configuração antes de validar CNPJ; ativação pelo admin fastPay | Cadastro mínimo não é autorização produtiva; H10/H39 |
| D-20 | Inadimplência suspende novos ciclos; reativação considera ciclo pago ainda válido | Histórico/dívida preservados; H41 |
| D-21 | Desistir do cancelamento até fim de ciclo, sem duplicar cobrança ou eliminar suspensão | Após encerrado, fluxo de reativação; H41 |
| D-22 | Login consumidor opcional; solicitação de exclusão registrada mesmo com pendência | Retenção necessária até resolver; prazos P-08; H47 |
| D-23 | Jobs não financeiros 5 tentativas totais; financeiro incerto continua | Retry idempotente e autorizado; H04/H30 |
| D-24 | Hostinger Docker; HML Windows/WSL2; futura AWS | Não montar cluster/Microservices no MVP; H02/H50 |
| D-25 | SES prod/Gmail HML; S3 prod/local HML; domínio configurável | Configuração real/contas ainda necessárias; H12/H49/H50 |
| D-26 | PITR prod 30 dias; full semanal/incremental diário/WAL; RPO 5 minRTO 2 h | HML pontual, ensaio obrigatório; H51 |
| D-27 | Logo opcional por estabelecimento | Admin altera/remove; fallback nome/iniciais; H13 |
| D-28 | Git Flow develop,1 agente e1 história por vez | Revoga proposta anterior de lotes de histórias; AGENTS |
| D-29 | Branch/commit/push/PR autorizados; merge/deploy separados | Concluída só merge develop+checks; AGENTS/progresso |
| D-30 | Emissão fiscal no fastPay na fase 2 | MVP emissão externa validada; FR-65 |
| D-31 | Diego responsável pelo piloto/suporte, contato manual e alertas por e-mail | Horários acordados; não 24 h presumido; H48/H52/H54 |
| D-32 | Gate piloto inclui fluxo completo, dinheiro, isolamento, restore e operação | Falhas graves bloqueiam; H54 |
| D-33 | Cota de IA em falhas: não aplicável | Não há recurso de IA no PRD; não criar cobrança ou dependência LLM |

## 2. Pendências de produto e validação externa

| ID / estado | O que ainda falta; alternativas a avaliar | Momento e histórias afetadas | Evidência para encerrar |
|---|---|---|---|
| P-01 — Aberta | Provar arranjo Mercado Pago: OAuth/recebedor, Pix/crédito/débito, split, taxas, refunds/saldo insuficiente, chargeback, relatórios, mensalidade da plataforma; rate limits e janela histórica | H05/H06; bloqueia conclusão integrada de H30/H35/H37/H40 e piloto | Relatório versionado com cenários, referências oficiais/contrato e resultados; limitações explícitas e aceite Diego |
| P-02 — Parcial | Cadastro mínimo e ativador definidos. Falta CPF/CNPJ aceito por PSP, validação objetiva de titular/responsável e checklist documental | H10 pode cadastrar configuração; gate produtivo H39/H54 | Checklist de ativação validado com PSP/Diego, nenhum campo fictício obrigatório |
| P-03 — Aberta | Contratos, responsabilidade regulatória/fiscal/privacidade de loja e plataforma; operação fiscal externa inclusive mensalidade/comissão | Antes de dinheiro real/piloto H54; fase 2 H fiscal futura | Validação profissional/contratual e procedimento externo. Este pacote não é parecer |
| P-04 — Aberta | Quem suporta perdas/tarifas/chargeback e efeito final sobre comissão; executar ajuste quando split não devolver igual | H37/H40, antes de cartão produtivo | Regra comercial aprovada, cobertura PSP e exemplos contábeis conciliados |
| P-05 — Resolvida para fluxo padrão | Suspensão pausa ciclos por inadimplência; administrativa registra cobrar/pausar; reativação por plataforma após regularização/acordo, usa ciclo pago ainda vigente sem estender ou novo antecipado; desfazer cancelamento antes da data | H41; acordo excepcional não gera renegociação automática | Decisão consolidada D-20/D-21; registrar termos de qualquer acordo real |
| P-06 — Parcial | Reconciliação e responsável definidos. Falta tela/permissão e procedimento de regularização manual sem reenviar produção; contatos/horários concretos de suporte | H48/H54; não bloqueia base de H30 | Runbook ensaiado com operações históricas/externas e reconciliação após indisponibilidade |
| P-07 — Parcial | TTL/sessões/TOTP/códigos definidos. Falta política de senha, rate limits, prova objetiva para recuperação avulsa e excepcional MFA, inclusive único admin fastPay | Política senha antes de concluir H08; excepcional em H09/H15 e antes de uso produtivo | Critérios de identidade, trilha/operador, prevenção de tomada de conta e testes aprovados |
| P-08 — Parcial | Exclusão pode ser solicitada com pendências; desativação de vínculo não exclui identidade. Falta matriz de retenção por dado, bases/finalidades, anonimização, exportação pessoal, backups e término de contrato | H47/H51/H54; não implementar limpeza destrutiva sem regra | Matriz validada, processo de atendimento, prazo e teste de retenção/expiração. Não escolher “5 anos” genérico |
| P-09 — Aberta | Mínimo de lojas, dominância, supressão por recortes cruzados e transparência contratual para dados comerciais | Bloqueia exportação a parceiros H46 e aceite completo correspondente | Regra numérica aprovada e testes de inferência; manter saída desabilitada até lá |
| P-10 — Resolvida | Arredondamento, desconto fixo, couvert, transferência/chamados e configuração capturada definidos | H14/H22/H25/H26 | D-08–D-12 e PRD3.0; testes nas histórias |
| P-11 — Parcial | Dicionário e distribuição monetária definidos. Falta política de reclassificação futura de produto da rede: visão histórica, atual ou ambas claramente rotuladas | Parte histórica de H44/H46 | Diego aprova apresentação e preservação de snapshots, com exemplo antes/depois |
| P-12 — Parcial | Infra de métricas definida. Falta SLO disponibilidade, janelas/percentis NFR06–08, browsers suportados, alvo acessibilidade, mix/tempo de carga e margem de recursos | H53/H54; frontend básico pode avançar acessível | Plano de medição aprovado e relatório de carga real, sem percentis inventados |
| P-13 — Aberta | Dados mínimos Google/PSP, transparência e associação de conta Google com equipe de mesmo e-mail | H27 e autenticação produtiva | Fluxo de identidade sem fusão automática, escopos mínimos e aceite de privacidade |
| P-14 — Parcial | Gate liberação definido. Falta duração de observação, ativo/adoção e denominadores, metas numéricas para expansão | H54 e expansão para 15 | Plano de piloto/observação aprovado; não bloquear medir por falta de meta inventada |
| P-15 — Aberta, fase 2 | Documentos fiscais/território, provedor ou próprio, credenciais, cancelamento e contingência | Antes de histórias fiscais futuras; não bloqueia MVP se emissão externa validada | PRD/especificação fase 2; manter compromisso explícito |

## 3. Gates técnicos de implementação

Estes pontos não exigem nova entrevista para escolhas rotineiras dentro da arquitetura. O agente pode propor/validar opções, registrar evidência e avançar, solicitando decisão de Diego quando houver custo, mudança de stack ou comportamento de produto.

| ID | Pendência técnica | Gate / responsável | Encerramento |
|---|---|---|---|
| T-01 — Parcial (H01) | Patches exatos, major PostgreSQL, compatibilidade Boot/Modulith, pgBackRest, plugins Java25, Playwright e npm. H01 fixou JDK/Maven/Boot/Modulith/JaCoCo/Angular/Node/npm e provou Boot 4.1.1+Modulith 2.1.1+Java 25 (contexto e verificação de módulos), ver evidencias/H01-build.md. Faltam PostgreSQL/digest, Testcontainers/Flyway (H03), registry de eventos (H04), pgBackRest, Playwright e PIT | H01/H03/H04, agente de desenvolvimento | Lockfiles/wrappers/imagens e smoke/migration/eventrecovery; versões registradas e reproduzíveis |
| T-02 | Linux/proxy/collector, DNS/tunnel, região S3, acesso SES/Google/PSP/GitHub, orçamento, e-mails reais | H12/H49–H52, Diego+implementação | Inventário sem secrets, URLs, conta/acessos mínimos e testes; contratar/provisionar depende autorização específica |
| T-03 | Limites monetários/texto/dimensões, export volume, quotas/rate limits e backoff técnico exato | Antes do endpoint afetado em H08/H13/H16/H22/H45 | Valores documentados, erros claros e testes de fronteira; não inventar limite comercial de loja |
| T-04 | Inspeção/quarentena de uploads e recuperação/versionamento dos objetos | H13/H36/H49/H51 | Política validada, teste arquivo malformado/malicioso e recuperação de evidência |
| T-05 | Bootstrap de único admin fastPay e acesso emergencial seguro | H09/H10 antes de primeiro ambiente produtivo | Processo fora de cadastro público, MFA e rotação/auditoria ensaiados; sem credencial padrão |

## 4. Modelo de nova decisão

- ID e título:
- Estado: aberta / parcial / resolvida / não aplicável.
- Problema concreto e histórias/critério bloqueados:
- Opções e impacto (produto, dados, segurança, custo):
- Recomendação fundamentada:
- Decisão e limites:
- Aprovador e data real:
- Evidência sanitizada ou referência oficial:
- Documentos/testes atualizados:
- Próxima revisão, se necessária:

Não transformar uma pendência técnica em desculpa para ignorar o comportamento acordado. Não marcar item parcialmente resolvido como gate globalmente liberado quando parte produtiva continuar pendente.
