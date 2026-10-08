# Épicos e histórias — fastPay

Versão 1.0 · 06/10/2026. 11 épicos e 54 histórias propostas para executar o escopo aprovado. IDs são estáveis, sem estimativas fictícias. A decomposição é um plano de execução; não significa implementação concluída.

## Regras de execução

Uma história por vez. Dependências são obrigatórias para concluir a história; integração com fluxo posterior pode ser preparada por contrato e fixture de teste, explicitando o que falta. Uma fixture de unidade ativa não libera produção. H39 prepara ativação; H40 integra antecipação da mensalidade, e ambas precisam estar concluídas antes do piloto. O fluxo produtivo nunca pode contornar um gate externo.

Se a próxima numeração estiver bloqueada, pode-se escolher a próxima elegível após registrar a troca; numeração não é obrigação de parar histórias independentes. Casos grandes podem ser subdivididos com sufixos (H30a etc.), preservando critérios e dependências, com atualização do backlog antes de executar; não anunciar “concluída” enquanto o escopo pai estiver pendente. Não executar sub-histórias em paralelo.

Cada história inclui UI e backend quando há interação, contratos, migrations, permissões, erros, estados de carregamento/vazio, auditoria e testes do comportamento afetado. Não construir apenas uma tela desconectada para satisfazer um fluxo. Todas excluem funcionalidades de fase 2/futuras e refatoração não relacionada. H05/H06 são provas, não implementações produtivas.

Critérios abaixo são cumulativos ao PRD/especificação e às [regras dos agentes](../AGENTS.md). Toda mutação tem autorização/tenant/idempotência aplicáveis; todo arquivo/SSE/export respeita acesso; toda história atualiza progresso e evidências. “Concluída” depende de checks e merge em develop; publicação é outra etapa.

## Índice

| ID | Épico | História | Dependências | Gate adicional |
|---|---|---|---|---|
| H01 | E01 | Bootstrap reproduzível e contratos de qualidade | — | T-01 |
| H02 | E01 | Ambientes Docker e pipeline GitHub | H01 | T-02 apenas acesso remoto |
| H03 | E01 | Migrations e fundação de isolamento RLS | H01, H02 | T-01 |
| H04 | E01 | Eventos duráveis, idempotência e jobs | H03 | T-01 |
| H05 | E02 | Prova PSP: recebedor, cobrança e split | H01, H02 | P-01 / acesso PSP |
| H06 | E02 | Prova PSP: devolução, conciliação e mensalidade | H05 | P-01, P-04 |
| H07 | E03 | Identidade, vínculo e seleção de unidade | H03 | — |
| H08 | E03 | Login da equipe e sessões seguras | H07 | P-07 política senha; T-03 |
| H09 | E03 | TOTP, códigos e bootstrap administrativo | H08 | P-07 recuperação excepcional; T-05 |
| H10 | E03 | Criar estabelecimento e convidar dono | H09, H12 | P-02 só ativação; T-05 |
| H11 | E03 | Equipe, permissões e concessão de suporte | H10 | — |
| H12 | E03 | Convites e e-mail durável em homologação | H04, H08 | T-02 conta de teste |
| H13 | E04 | Catálogo, setores, disponibilidade e logo | H11 | T-03 limites mídia; T-04 |
| H14 | E04 | Configurações versionadas por estabelecimento | H11 | P-10 resolvida |
| H15 | E04 | Comanda individual, visita e QR avulso | H13, H14 | P-07 prova de recuperação |
| H16 | E04 | Rascunho, envio atômico e preço capturado | H04, H15 | T-03 |
| H17 | E05 | Sincronização operacional SSE e reconexão | H04, H15, H16 | — |
| H18 | E05 | Fila de produção e entrega por linha | H13, H16, H17 | — |
| H19 | E05 | Operações em lote de produção | H18 | D-04 resolvida |
| H20 | E06 | Atendimento responsável ou central | H11, H14, H18 | — |
| H21 | E06 | Problemas do consumidor e conclusão autorizada | H15, H20 | — |
| H22 | E06 | Composição, serviço e descontos exatos | H14, H16 | T-03 monetários |
| H23 | E06 | Cancelar quantidade e alertar produção | H18, H21, H22 | — |
| H24 | E06 | Desfazer entrega com histórico | H18, H23 | — |
| H25 | E06 | Transferir unidades sem duplicar produção | H21, H22, H23, H24 | P-10 resolvida |
| H26 | E06 | Continuação de visita e ajustes de couvert | H15, H22, H23 | — |
| H27 | E07 | Google opcional e histórico do consumidor | H15 | P-13 |
| H28 | E07 | Conferência, bloqueio e reabertura | H21, H22, H25, H26 | — |
| H29 | E07 | Encerramento de conta zerada | H28 | D-02 resolvida |
| H30 | E07 | Pagamento integrado e reconciliação de incerteza | H05, H06, H04, H28 | P-01 |
| H31 | E07 | Recebimento externo e invalidação | H28, H30 | — |
| H32 | E07 | Saída automática ou validada | H29, H30, H31 | — |
| H33 | E07 | Comprovante não fiscal em PDF | H29, H30, H31, H32 | — |
| H34 | E08 | Encerrar débito e regularizar depois | H30, H31, H32 | — |
| H35 | E08 | Devolução integrada atribuída ou livre | H06, H30, H22 | P-01 |
| H36 | E08 | Devolução externa com evidências | H31, H35, H13 | T-04 |
| H37 | E08 | Conciliação financeira e chargeback | H06, H30, H35, H36 | P-01, P-04 |
| H38 | E09 | Planos e condições com vigência | H10, H11 | — |
| H39 | E09 | Checklist e ativação pelo admin fastPay | H30, H38 | P-02, P-03 na produção |
| H40 | E09 | Mensalidade Pix e comissão por pagamento | H06, H35, H38 | P-01, P-04 |
| H41 | E09 | Suspensão, cancelamento e reativação | H39, H40 | P-05 resolvida |
| H42 | E10 | Dashboard financeiro e calendário operacional | H34, H37, H40, H41 | P-11 parcialmente |
| H43 | E10 | Métricas operacionais e ranking local | H24, H25, H35, H42 | — |
| H44 | E10 | Catálogo de referência da rede | H13, H11 | P-11 reclassificação histórica |
| H45 | E10 | Exportações CSV assíncronas | H42, H43, H04 | T-03 volume |
| H46 | E10 | Indicadores da rede e exportação protegida | H43, H44, H45 | P-09, P-11 |
| H47 | E11 | Privacidade, exclusão e retenção | H27, H34, H35, H41 | P-08, P-13 |
| H48 | E11 | Contingência manual e procedimento de suporte | H32, H34, H36 | P-06 |
| H49 | E11 | S3 privado e SES produtivo | H12, H13, H36, H45 | T-02, T-04, P-08 |
| H50 | E11 | Deploy manual Hostinger, domínio e tunnel | H02, H17, H49 | T-02 |
| H51 | E11 | Backup PITR e restauração cronometrada | H49, H50 | T-01, T-02, T-04, P-08 |
| H52 | E11 | Monitoramento e alertas externos | H04, H30, H50, H51 | T-02 |
| H53 | E11 | Jornadas, segurança, acessibilidade e carga | H32, H33, H34, H35, H36, H41, H43, H45, H47, H48, H52 | P-12 |
| H54 | E11 | Aceite e liberação do piloto acompanhado | H39, H40, H46, H48, H51, H52, H53 | Todos os gates produtivos; Diego |

## E01 — Base do repositório, ambientes, pipeline e testes

### H01 — Bootstrap reproduzível e contratos de qualidade

**Objetivo/escopo e aceite:** Criar backend e frontend mínimos nas versões fixadas, wrappers/lockfiles e README executável; servir tela e endpoint de saúde sem implementar regra comercial; configurar lint, Vitest, JUnit, JaCoCo e teste de fronteiras Modulith; registrar versões e incompatibilidades sem atualizar stack silenciosamente.

**Dependências:** —. **Referências:** NFR-01, NFR-03. **Gate:** T-01.

**Validação exigida:** Build limpo em ambiente novo; scripts CI executáveis; prova de integração Boot/Modulith/Java25 e relatório de versões.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H02 — Ambientes Docker e pipeline GitHub

**Objetivo/escopo e aceite:** Criar Compose dev/HML e imagens backend/frontend; env.example sem segredo, DB interno persistente; Actions testa PR e publica artefatos/imagens privados conforme política; repositório usa develop/main; não fazer deploy automático.

**Dependências:** H01. **Referências:** NFR-04, NFR-11. **Gate:** T-02 apenas acesso remoto.

**Validação exigida:** Build de imagens, restart sem perder DB, execução WSL2 e CI com artefatos; se remoto ausente registrar bloqueio, sem inventar PR.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H03 — Migrations e fundação de isolamento RLS

**Objetivo/escopo e aceite:** Criar schema inicial pelo Flyway e papéis migration/runtime; contexto de tenant transacional; FKs escopadas; separar tabelas globais; Hibernate validate e policies fail-closed; preparar Testcontainers com o mesmo papel runtime.

**Dependências:** H01, H02. **Referências:** NFR-02, CA-03. **Gate:** T-01.

**Validação exigida:** Migration em banco vazio/atualização; acesso cruzado, tenant ausente, pool após rollback e usuário sem BYPASSRLS negados.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H04 — Eventos duráveis, idempotência e jobs

**Objetivo/escopo e aceite:** Persistir intenção/evento na transação, dedup por efeito e retry recuperável;5 tentativas para não financeiros, fila de falhas e retomada autorizada; worker estabelece tenant; não impor teto a conciliação financeira.

**Dependências:** H03. **Referências:** NFR-03, FR-15, FR-27. **Gate:** T-01.

**Validação exigida:** Restart entre commit/efeito, evento duplicado, rollback sem publicação, duas execuções concorrentes e isolamento de job.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E02 — Validação técnica e comercial do PSP

### H05 — Prova PSP: recebedor, cobrança e split

**Objetivo/escopo e aceite:** Investigar contrato oficial e contas de teste; provar vínculo de recebedor, Pix/crédito/débito disponível, split/taxas e cobrança para conta correta; documentar idempotência, autenticação de webhook, rate limits e incerteza; não construir fluxo produtivo completo.

**Dependências:** H01, H02. **Referências:** FR-34–36, FR-46–48, P-01. **Gate:** P-01 / acesso PSP.

**Validação exigida:** Relatório com requisições/respostas sanitizadas e IDs de teste, cenários suportados/indisponíveis e decisão Diego; mock não encerra história.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H06 — Prova PSP: devolução, conciliação e mensalidade

**Objetivo/escopo e aceite:** Provar refunds parciais/totais, saldo insuficiente, credencial revogada, eventos tardios, dois pagamentos e chargeback; verificar relatórios/liquidação/disponibilidade, reversão de comissão e cobrança Pix da plataforma; levantar limitações contratuais.

**Dependências:** H05. **Referências:** FR-44–48, FR-51–52, P-01, P-04. **Gate:** P-01, P-04.

**Validação exigida:** Matriz de resultados comprovados, incluindo limites do sandbox e evidência externa necessária; aprovar adaptação antes de implementar arranjo incompatível.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E03 — Estabelecimentos, usuários, autenticação e permissões

### H07 — Identidade, vínculo e seleção de unidade

**Objetivo/escopo e aceite:** Modelar identidade global separada de membership tenant; selecionar unidade autorizada visível; trocar contexto limpa frontend; desativar vínculo preserva histórico/outros acessos; acesso global não concede operação de loja.

**Dependências:** H03. **Referências:** FR-03, FR-06, CA-03. **Gate:** —.

**Validação exigida:** Testes backend/UI de duas lojas, IDs forjados, vínculo revogado e identidade com papéis distintos.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H08 — Login da equipe e sessões seguras

**Objetivo/escopo e aceite:** Email/senha, Spring Session JDBC e cookie seguro, CSRF e expiração por perfil; reset 30 min single-use revoga sessões e mantém MFA; mensagens não enumeram contas; autenticação ainda usa remetente de teste até H12.

**Dependências:** H07. **Referências:** FR-04–06, NFR-04. **Gate:** P-07 política senha; T-03.

**Validação exigida:** Expiração idle/absoluta com relógio controlado, polling não renova, fixação/CSRF, tentativas abusivas e reset repetido/expirado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H09 — TOTP, códigos e bootstrap administrativo

**Objetivo/escopo e aceite:** Exigir MFA privilegiado e impedir promoção sem configuração;10 códigos de uso único; senha+código permite recadastro restrito, invalida antigos/sessões e notifica; bootstrap admin fastPay sem signup público/credencial padrão; documentar recuperação excepcional aprovada.

**Dependências:** H08. **Referências:** FR-05, FR-06, P-07. **Gate:** P-07 recuperação excepcional; T-05.

**Validação exigida:** TOTP repetido/janela, código concorrente, reset não remove MFA, promoção e perda do único admin conforme procedimento validado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H10 — Criar estabelecimento e convidar dono

**Objetivo/escopo e aceite:** AdminfastPay cria nome/email/ID e CNPJ quando informado, estado configuração; convite 48 h e aceite; conta existente autentica para vincular; cadastro não ativa nem gera mensalidade; reenvio invalida convite anterior.

**Dependências:** H09, H12. **Referências:** FR-01–02, FR-66, P-02. **Gate:** P-02 só ativação; T-05.

**Validação exigida:** Duplicidade de aceite, token vencido, tentativa por loja/consumidor, ausência CNPJ permitida em configuração e nenhum fluxo produtivo liberado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H11 — Equipe, permissões e concessão de suporte

**Objetivo/escopo e aceite:** Dono convida papéis; gerente delega apenas ações permitidas; preservar último admin; desativação eficaz em sessão; suporte somente leitura temporária concedida pelo dono com motivo e auditado, sem impersonação.

**Dependências:** H10. **Referências:** FR-02–06, FR-66, CA-04, CA-28. **Gate:** —.

**Validação exigida:** Matriz de acesso por comando incluindo devolução não delegável; revogação/expiração e tentativa de suporte escrever ou ver outra loja.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H12 — Convites e e-mail durável em homologação

**Objetivo/escopo e aceite:** EmailPort e templates de convite/reset/avisos, Gmail HML dedicado e caixa dev; retries/erros visíveis; links usam baseURL configurada; proteger destinatários de teste; autorização de reenvio igual origem.

**Dependências:** H04, H08. **Referências:** FR-02, FR-04, FR-27. **Gate:** T-02 conta de teste.

**Validação exigida:** Falha SMTP, cinco tentativas, reenvio idempotente, link expirado, segredo ausente e destinatário indevido bloqueado; sem envio real não autorizado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E04 — Catálogo, configurações e abertura de comanda

### H13 — Catálogo, setores, disponibilidade e logo

**Objetivo/escopo e aceite:** CRUD autorizado de produto/setor e disponibilidade, uma foto opcional; logo opcional com substituição/remoção e fallback; storage local por interface, verificação real de formato/tamanho; produção sem valores financeiros.

**Dependências:** H11. **Referências:** FR-13–14, FR-18, FR-67. **Gate:** T-03 limites mídia; T-04.

**Validação exigida:** Permissão, imagem inválida/tamanho/dimensões, fileID de outra unidade, produto esgotado e logo removido.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H14 — Configurações versionadas por estabelecimento

**Objetivo/escopo e aceite:** Configurar serviço/bases/couvert/limites desconto/modos atendimento e saída/fuso/corte; snapshots nos momentos aprovados; alterações com vigência não reescrevem comanda/período; validar parâmetros e auditar.

**Dependências:** H11. **Referências:** FR-23, FR-28–30, FR-40, FR-57, FR-68. **Gate:** P-10 resolvida.

**Validação exigida:** Comandas antes/depois de mudança mantêm snapshot correto; alteração de permissão imediata; limite percentual e configuração inválida recusados.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H15 — Comanda individual, visita e QR avulso

**Objetivo/escopo e aceite:** Funcionário abre tab e visita com mesa opcional; QR2 min single-use gera grant exclusivo; preview não consome; consumidor avulso consulta sem login; recuperar revoga anterior após prova aprovada; expiração suspensa enquanto pendente; só unidade ativa pode abrir em produção.

**Dependências:** H13, H14. **Referências:** FR-07–12, CA-01–02, CA-23. **Gate:** P-07 prova de recuperação.

**Validação exigida:** QR simultâneo, substituído/expirado, acesso por nome negado, grant após fechar browser e após resolução+7 dias; fixture ativa de teste não substitui H39.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H16 — Rascunho, envio atômico e preço capturado

**Objetivo/escopo e aceite:** Garçom monta até 50 linhas/99 unidades cada; preço mudado exige reconfirmação; indisponível mantém tudo rascunho; envio grava linha/alocação/snapshot e evento, versão aberta sob lock; timeout consulta mesma operação, não duplica.

**Dependências:** H04, H15. **Referências:** FR-14–16, CA-05–07. **Gate:** T-03.

**Validação exigida:** Envio repetido, fechamento concorrente com stub contratual, mudança preço/esgotado, restart entre gravação e produção e limites exatos.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E05 — Pedidos, cozinha/bar e entrega

### H17 — Sincronização operacional SSE e reconexão

**Objetivo/escopo e aceite:** SSE por contexto e polling fallback; resync no foco/reconexão, dados stale visíveis; heartbeat não prolonga sessão; cancelar streams ao revogar/trocar unidade; rascunho sem autoenvio; definir cache PWA privado seguro.

**Dependências:** H04, H15, H16. **Referências:** FR-27, NFR-02, NFR-06–07, NFR-11. **Gate:** —.

**Validação exigida:** Queda/reconexão/cursor perdido, replay, usuário sem vínculo e sessão expirada; sessão não renovada pelo heartbeat.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H18 — Fila de produção e entrega por linha

**Objetivo/escopo e aceite:** Aguardando→em preparo→pronto→entregue por linha; sem preparo pronto e classe separada; uma sequência para toda quantidade; mostrar destinos/alocações, atraso e observações sem valores; impossibilidade tem motivo e bloqueia conclusão até correção.

**Dependências:** H13, H16, H17. **Referências:** FR-18–22, CA-08. **Gate:** —.

**Validação exigida:** Permissões setor, não avançar impossível, entrega com versão desatualizada, relógios de etapa e linha única para quantidade maior que 1.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H19 — Operações em lote de produção

**Objetivo/escopo e aceite:** Até 50 linhas com estado/versão esperados; executar resultados por linha, sem reverter sucessos por conflito alheio; retornar lista de concluídas/ignoradas/falhas e motivo; replay não avança duas etapas.

**Dependências:** H18. **Referências:** FR-19, FR-69. **Gate:** D-04 resolvida.

**Validação exigida:** Lote misto com cancelada/conflitante/permitida, autorização item a item, limite 51 e retry do mesmo lote.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E06 — Atendimento, correções, descontos e transferências

### H20 — Atendimento responsável ou central

**Objetivo/escopo e aceite:** Modo capturado na abertura; responsável inicial e reatribuição gerente, ou central com claim exclusivo; fila de prontos/impossibilidade/problemas; entrega conclui aviso, leitura não conclui tarefa; sem botão extra chamar atendimento.

**Dependências:** H11, H14, H18. **Referências:** FR-23, FR-26–27, FR-68. **Gate:** —.

**Validação exigida:** Dois funcionários assumem simultaneamente, ajuda permitida, reatribuição e troca de modo sem migrar chamadas antigas.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H21 — Problemas do consumidor e conclusão autorizada

**Objetivo/escopo e aceite:** Informar problema por item/conta, acompanhar aberta/em atendimento/tratada; ação tratar com permissão delegável, resolução obrigatória e correção realizada; problema bloqueia cobrança futura sem cancelar existente; pós-pagamento preserva quitação.

**Dependências:** H15, H20. **Referências:** FR-24–25, CA-14. **Gate:** —.

**Validação exigida:** Cliente de outra comanda, tratar sem permissão, cobrança em andamento, correção sem concluir chamado e caso pago encaminhado a gerente.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H22 — Composição, serviço e descontos exatos

**Objetivo/escopo e aceite:** Calcular C/D/K/S com BigDecimal e HALF_UP2, opção consumidor retirar serviço antes de cobrança; formato e autoridade de desconto com motivo; redução de C limita desconto fixo e marca revisão se excede limite; snapshot e total não negativo.

**Dependências:** H14, H16. **Referências:** FR-28–30, P-10, FR-68. **Gate:** T-03 monetários.

**Validação exigida:** Tabela de bases serviço, centavos, retirada, desconto 100%, overflow/limite, redução pós-concessão e autorização backend.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H23 — Cancelar quantidade e alertar produção

**Objetivo/escopo e aceite:** Cancelar quantidade inteira parcial/total com permissão/motivo; manter original/cancelado/remanescente; antes preparo ajustar fila, depois exigir ciência sem bloquear correção; entregue preserva evento; conta paga direciona devolução sem apagar fatos.

**Dependências:** H18, H21, H22. **Referências:** FR-20–21, CA-09. **Gate:** —.

**Validação exigida:** Duas correções concorrentes, qty inválida, cancelamento antes/durante/depois, ciência repetida, desconto revisão e pagamento existente.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H24 — Desfazer entrega com histórico

**Objetivo/escopo e aceite:** Gerente/permissão específica desfaz com motivo quando TODAS alocações ativas em comandas abertas/sem cobrança; linha volta pronto; evento fica invalidado para métrica; se paga/encerrada registrar ocorrência, sem refund automático.

**Dependências:** H18, H23. **Referências:** FR-70. **Gate:** —.

**Validação exigida:** Destinos múltiplos um fechado, corrida com fechamento, permissão revogada e nova entrega substitui amostra antiga.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H25 — Transferir unidades sem duplicar produção

**Objetivo/escopo e aceite:** Transferir parcial ou toda quantidade entre tabs abertas da mesma unidade, atomicamente com motivo/permissão; preservar linha/preço/etapas e atualizar alocações; problema do item bloqueia item, conta bloqueia entradas/saídas; avaliar desconto nas duas.

**Dependências:** H21, H22, H23, H24. **Referências:** FR-17, RN-01, CA-10. **Gate:** P-10 resolvida.

**Validação exigida:** Rollback entre contas, lock em ordem, divisão de 3 unidades em 2 + 1 com produção única, tentativa fora da unidade, linha entregue e conflito de destinos na entrega.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H26 — Continuação de visita e ajustes de couvert

**Objetivo/escopo e aceite:** Vincular mesma visita por autorizado, sem inferência por nome; auto/manual captura valor correto; manter dispensa indevido/isenção, duplicidade mantém válido e correção exige inclusão explícita; saída validada encerra continuidade, automática não prova saída.

**Dependências:** H15, H22, H23. **Referências:** FR-12, FR-29, FR-68. **Gate:** —.

**Validação exigida:** Virada do dia, segunda comanda, nomes iguais, refund motivo distinto simulado por contrato até H35 e ausência de recobrança implícita.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E07 — Conferência, fechamento, pagamentos e saída

### H27 — Google opcional e histórico do consumidor

**Objetivo/escopo e aceite:** Login Google com escopos mínimos/state/nonce e sessões aprovadas; recusa mantém avulso; associar só com grant válido e confirmação; histórico autorizado entre lojas sem expor terceiros; não fundir equipe por e-mail automaticamente.

**Dependências:** H15. **Referências:** FR-04, FR-10–11, P-13. **Gate:** P-13.

**Validação exigida:** OAuth com falha/negado, conta Google diferente, login sem grant, associação repetida, privacidade e prazos de sessão.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H28 — Conferência, bloqueio e reabertura

**Objetivo/escopo e aceite:** Conferência exige enviado entregue/cancelado e problemas resolvidos; rascunho não bloqueia mas não envia depois; lock consolida versão/total; ajustar serviço antes cobrança; reabrir só autorizado/motivo após resolução definitiva da tentativa; paga não reabre consumo.

**Dependências:** H21, H22, H25, H26. **Referências:** FR-31–33, CA-07–08, CA-11. **Gate:** —.

**Validação exigida:** Corridas envio/cancelamento/transferência vs fechamento; pronta não entregue; reopen com cobrança incerta e rascunho preservado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H29 — Encerramento de conta zerada

**Objetivo/escopo e aceite:** Consumidor encerra conta zero elegível sem autorização ou PSP; registrar sem valor, não pagamento; manter requisitos de pendência/produção e modo de saída; funcionário encerra abandono vazio auditado.

**Dependências:** H28. **Referências:** FR-41–42, CA-20. **Gate:** D-02 resolvida.

**Validação exigida:** Conta sem pedidos, desconto 100% com/sem serviço/couvert, problema aberto, cobrança incerta e ausência de pagamento/comissão fictícios.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H30 — Pagamento integrado e reconciliação de incerteza

**Objetivo/escopo e aceite:** Admin conecta conta PSP; Pix/cartão integral pelo contrato validado, intenção idempotente, webhook autenticado+inbox e consulta; versão/valor/recebedor/BRL conferidos; consulta inicial após 15 s até 5 min e alerta 15 min, continua incerto; dois pagamentos geram excedente para gerente; revogação bloqueia novos sem perder antigos.

**Dependências:** H05, H06, H04, H28. **Referências:** FR-34–36, FR-39, FR-46–48, CA-15–17. **Gate:** P-01.

**Validação exigida:** Timeout após criar/aprovar, webhook perdido/repetido/tardio/inválido, conta errada, duas aprovações distintas, retry manual e restart; E2E provedor com evidência.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H31 — Recebimento externo e invalidação

**Objetivo/escopo e aceite:** Receber integral dinheiro/maquininha por autorizado com referência aplicável; não coexistir com incerto; gerente invalida registro com motivo preservando histórico/restaurando dívida; não movimenta dinheiro nem apaga saída passada; refund existente exige análise.

**Dependências:** H28, H30. **Referências:** FR-37–38, CA-19. **Gate:** —.

**Validação exigida:** Dois registros concorrentes, falta de permissão, tentativa PSP pendente e diferença entre invalidação e devolução real.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H32 — Saída automática ou validada

**Objetivo/escopo e aceite:** Modo da comanda preservado; quitada/zero autoriza conforme política; validação staff por QR específico ou painel consulta servidor; replay mostra anterior; débito/incerto não autoriza; invalidação revoga saída não usada preservando validada.

**Dependências:** H29, H30, H31. **Referências:** FR-40, CA-22. **Gate:** —.

**Validação exigida:** Print/replay, QR vínculo usado como saída, dois validadores, queda de rede e ajuste após saída histórica.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H33 — Comprovante não fiscal em PDF

**Objetivo/escopo e aceite:** Download autenticado da conta com logo/nome, itens/preços, C/D/K/S, total e situação; zero é demonstrativo; snapshot e versionamento para nova emissão após ajustes; aviso não fiscal; sem envio automático.

**Dependências:** H29, H30, H31, H32. **Referências:** FR-64–65. **Gate:** —.

**Validação exigida:** Comparar centavos/total com snapshot, autorização entre tabs, fonte/acento/paginação e alteração após refund contratual.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E08 — Devoluções, conciliação e débitos

### H34 — Encerrar débito e regularizar depois

**Objetivo/escopo e aceite:** Gerente encerra com dívida/motivo após resolver produção/cobrança incerta; não receita/saída; gerente regulariza integral via meio aprovado no dia efetivo sem novo consumo; novo pagamento usa condição comercial vigente, preserva original.

**Dependências:** H30, H31, H32. **Referências:** FR-42–43, CA-21. **Gate:** —.

**Validação exigida:** Abandono com pedido pendente, duplicidade de regularização, acesso avulso mantido e comissão só ao integrado aprovado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H35 — Devolução integrada atribuída ou livre

**Objetivo/escopo e aceite:** Gerente autoriza total/parcial com motivo/origem; atribuição saldo líquido item/serviço/couvert ou livre; reserva atômica global+componente, webhook/consulta confirma; incerto mantém reserva; comissão reverte acumulada, histórico permanece; aplicar motivo couvert.

**Dependências:** H06, H30, H22. **Referências:** FR-44, FR-51, CA-18, FR-71. **Gate:** P-01.

**Validação exigida:** Dois refunds concorrentes, exceder componente/global, livre seguido atribuído, saldo insuficiente, timeout/retry e integral em parcelas com centavos exatos.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H36 — Devolução externa com evidências

**Objetivo/escopo e aceite:** Gerente autoriza refund externo sem fingir transação PSP; registrar execução real por autorizado e até 3comprovantes10 MB; conservar original privado; respeitar reservas e limite, relatório distingue solicitado/concluído; atualizar PDF.

**Dependências:** H31, H35, H13. **Referências:** FR-44, FR-71. **Gate:** T-04.

**Validação exigida:** Permissão, duplicidade, comprovante de outra loja, arquivo inválido, saldo comprometido e PDF após ajuste; nenhum dinheiro movimentado pelo registro.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H37 — Conciliação financeira e chargeback

**Objetivo/escopo e aceite:** Visão de divergências com referência, aprovado/liquidado/disponível separados; rotina diária recentes+pendências antigas; receber contestação e alertar dono, defesa externa; resultado aplica política aprovada sem reabrir/redebitar automaticamente.

**Dependências:** H06, H30, H35, H36. **Referências:** FR-39, FR-45, FR-48. **Gate:** P-01, P-04.

**Validação exigida:** Relatório ausente não vira zero, credencial antiga revogada, evento chargeback repetido/tardio, divergência e reversão conforme contrato.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E09 — Planos, mensalidades e comissões

### H38 — Planos e condições com vigência

**Objetivo/escopo e aceite:** Planos 100+1%,200+0,6%,400+0,3%, mesmas funções; plataforma gerencia condições negociadas, dono consulta; histórico e vigência futura, alteração tabela não muda contratos antigos; gerente sem gestão assinatura.

**Dependências:** H10, H11. **Referências:** FR-49–50. **Gate:** —.

**Validação exigida:** Datas/ciclo, comissão original preservada e acesso indevido a condições comerciais negado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H39 — Checklist e ativação pelo admin fastPay

**Objetivo/escopo e aceite:** Configuração permite preparação sem operação real/mensalidade; admin fastPay verifica cadastro/PSP/configuração e ativa; registrar responsável/checklist; ativação ancora ciclo e exige cobrança antecipada conforme H40; fixtures HML não contêm vendas reais.

**Dependências:** H30, H38. **Referências:** FR-01, FR-53, FR-72. **Gate:** P-02, P-03 na produção.

**Validação exigida:** Ativar sem gates, repetir ativação, conta PSP inválida, flag HML tentando liberarprod e abertura de tab em configuração.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H40 — Mensalidade Pix e comissão por pagamento

**Objetivo/escopo e aceite:** Competência única, Pix para fastPay e confirmaçãoPSP; ativação/ciclo ancorado, último dia sem perder âncora; condições novas próximo ciclo sem prorata; comissão integrada no total aprovado e reversão original; externo/zero não comissiona; integrar conclusão de H39 antes piloto.

**Dependências:** H06, H35, H38. **Referências:** FR-50–53, CA-25. **Gate:** P-01, P-04.

**Validação exigida:** 31/jan → fim de fevereiro → 31/mar, retry competência, aprovação tardia, alteração plano, refunds fracionados e taxaPSP separada.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H41 — Suspensão, cancelamento e reativação

**Objetivo/escopo e aceite:** Inadimplência avisa sem auto suspender piloto; plataforma suspende com motivo/data/aviso, para novos ciclos por inadimplência; administrativa explicita cobrar/pausar; bloqueia novas tabs preserva fins/finanças; cancelamento fimciclo reversível até data; reativação mantém ciclo pago vigente ou novo antecipado sem perdoar dívida.

**Dependências:** H39, H40. **Referências:** FR-54–55, CA-24, FR-73. **Gate:** P-05 resolvida.

**Validação exigida:** Troca de estado concorrente com nova tab, futuro cancelamento revertido, suspensão paralela, ciclo remanescente e ausência mensalidade duplicada.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E10 — Indicadores, referência da rede e exportação

### H42 — Dashboard financeiro e calendário operacional

**Objetivo/escopo e aceite:** Projeções até 1 min, dataAsOf/última conciliação distintos e stale visível; bruto/desconto/serviço/couvert/pago/debito/refund/comissão/tarifa separados; corte versionado e período por unidade; dono só consolida vínculos atuais.

**Dependências:** H34, H37, H40, H41. **Referências:** FR-56–57, FR-60, FR-74. **Gate:** P-11 parcialmente.

**Validação exigida:** Mudança corte/fuso, evento tardio, perda vínculo, projeção falha/recuperada, valores recomputados da origem e pagamento/saída nunca usam projeção.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H43 — Métricas operacionais e ranking local

**Objetivo/escopo e aceite:** Uma amostra/linha nas etapas concluídas; sem preparo separado, pendentes idade e cancelados etapa/motivo; transferência não duplica, undo invalida amostra; ranking qty/valor com desconto maior resto, devolução atribuída/livre separada; médio/p95 por amostras reais.

**Dependências:** H24, H25, H35, H42. **Referências:** FR-58–59, FR-75. **Gate:** —.

**Validação exigida:** Dataset conhecido com quantidade 3, transferência de 2, cancelamento de 1, entregue/desfeito, pronto cancelado, sem preparo e centavo residual; conferir p95 sem média de percentis.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H44 — Catálogo de referência da rede

**Objetivo/escopo e aceite:** Plataforma mantém categoria/marca/produto/apresentação; loja vincula opcionalmente sem alterar catálogo/preço; não classificado explícito, cobertura; nomes iguais não se unem; aplicar política histórica aprovada.

**Dependências:** H13, H11. **Referências:** FR-62. **Gate:** P-11 reclassificação histórica.

**Validação exigida:** Mapeamento removido/trocado, prato próprio/categoria, produto homônimo e cobertura correta com dados sem vínculo.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H45 — Exportações CSV assíncronas

**Objetivo/escopo e aceite:** Até 90 dias, uma ativa por usuário/unidade, dedup e status/filtros/dataAsOf; pronto acessível 24 h mediante permissão atual; CSV protege fórmula e documenta fuso/decimais; artefato expira sem excluir registros.

**Dependências:** H42, H43, H04. **Referências:** FR-56, FR-60, FR-76. **Gate:** T-03 volume.

**Validação exigida:** 91 dias, duas solicitações, revogação entre criar/baixar, arquivo parcial, retry/expiração e injeção de fórmula.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H46 — Indicadores da rede e exportação protegida

**Objetivo/escopo e aceite:** Agregados para admin fastPay com cobertura, quantidade/valor/períodos e fuso da rede; separar demanda/quitadas/débito/ajustes; incluir externos sem comissão; relatório parceiro só após thresholds, sem consumidor/loja/preço individual e com auditoria.

**Dependências:** H43, H44, H45. **Referências:** FR-61–63, CA-27. **Gate:** P-09, P-11.

**Validação exigida:** Recortes pequenos/dominância/crossfilter, múltiplos meios, transferência e desclassificados; saída bloqueada enquanto regra P-09 não fechada.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## E11 — Operação, validação e piloto

### H47 — Privacidade, exclusão e retenção

**Objetivo/escopo e aceite:** Solicitação autenticada rastreável mesmo com pendências; minimizar dados retidos para resolver, encerrar/anonimizar conforme matriz aprovada; desativação staff≠excluir identidade; cancelamento loja separado; exportação pessoal e backups conforme política, não prazo inventado.

**Dependências:** H27, H34, H35, H41. **Referências:** NFR-15, P-08. **Gate:** P-08, P-13.

**Validação exigida:** Dívida/refund pendente, múltiplos vínculos/último admin, retenção legal por classe, aplicação após resolução e restore sem ressuscitar acesso revogado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H48 — Contingência manual e procedimento de suporte

**Objetivo/escopo e aceite:** Tela/procedimento autorizado de regularização histórica aprovada sem reenviar cozinha; explicar desconexão e conferência; Diego responsável, contatoWhatsApp manual/e-mail alertas/horários a preencher; equipe ensaia limites financeiros e PSP incerto.

**Dependências:** H32, H34, H36. **Referências:** NFR-11, FR-66, P-06. **Gate:** P-06.

**Validação exigida:** Pedido feito manualmente aparece sem nova produção, registros duplicados detectados, sem aprovação financeira offline e roteiro de atendimento validado.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H49 — S3 privado e SES produtivo

**Objetivo/escopo e aceite:** Adaptar storage S3 privado separado backup, autorizar download e preservar evidência; SES remetente/domínio verificado e acesso produtivo, bounce/complaint; secrets externos, HML mantém local/Gmail; verificar permissões sem divulgar tokens.

**Dependências:** H12, H13, H36, H45. **Referências:** NFR-04, FR-27, FR-67. **Gate:** T-02, T-04, P-08.

**Validação exigida:** Arquivo privado, acesso revogado, versionamento/recuperação, email aceito≠entregue, falha retry e rotação de credencial; produção só após autorização de provisionar.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H50 — Deploy manual Hostinger, domínio e tunnel

**Objetivo/escopo e aceite:** Compose prod com imagens digest, DB privado, recursos/health/proxyTLS, scripts migration/deploy/rollback documentados; URLs configuráveis; DNS Cloudflare/tunnel HML só callbacks; troca domínio antes de expirar e credenciais separadas; sem deploy automático.

**Dependências:** H02, H17, H49. **Referências:** NFR-04, NFR-16. **Gate:** T-02.

**Validação exigida:** Ensaio em HML com mesmas imagens, SSE proxy, restart, ausência secret imagem, rollback compatível e callback HTTPS; não executar DNS/deploy prod sem pedido.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H51 — Backup PITR e restauração cronometrada

**Objetivo/escopo e aceite:** pgBackRest full semanal/incremental diário/WAL contínuo S3, janela 30 dias com base; chave fora VPS; restore isolado medido RPO 5 min/RTO 2 h; reconciliar PSP e gaps externos antes liberar; incluir recuperação dos arquivos/chaves, não sóDB.

**Dependências:** H49, H50. **Referências:** NFR-12–13, CA-29. **Gate:** T-01, T-02, T-04, P-08.

**Validação exigida:** Restore timestamp escolhido, falha backup/WAL, íntegra de pedidos/recebimentos e objetos, sem e-mail/job produtivo em restore; plano mensal e antes piloto.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H52 — Monitoramento e alertas externos

**Objetivo/escopo e aceite:** Actuator/Micrometer/logsJSON seguros e coletor; GrafanaCloudfree respeita limites/retenção, disponibilidade/TLS externos e alertas para Diego; cobrir recursos/DB/jobs/PSP/email/backup; runbook por alerta e contato real.

**Dependências:** H04, H30, H50, H51. **Referências:** NFR-04, P-12. **Gate:** T-02.

**Validação exigida:** Derrubar app para comprovar alerta externo, WAL atrasado, job esgotado e pagamento 15 min; verificar ausência segredo/PII e cardinalidade.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H53 — Jornadas, segurança, acessibilidade e carga

**Objetivo/escopo e aceite:** Executar E2E ponta a ponta e matriz permissões/RLS, finanças extremas, frontend móvel/produção e acessibilidade nos browsers acordados; carga 3 unidades com 250 comandas e30 tentativas/min cada +mix, metas e correção; registrar capacidade real e limitações.

**Dependências:** H32, H33, H34, H35, H36, H41, H43, H45, H47, H48, H52. **Referências:** CA-01–30, NFR-01–16. **Gate:** P-12.

**Validação exigida:** Relatório de testes por commit/imagem, curvas latência/recursos e falhas reproduzíveis; gates financeiros sem exceção silenciosa; não chamar benchmark sintético de homologação PSP.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

### H54 — Aceite e liberação do piloto acompanhado

**Objetivo/escopo e aceite:** Checklist fluxo abrir→entregar→pagar→sair, duplicidade/incerteza/refund, isolamento, restore, alertas e equipe; validar cadastros/contratos/privacidade/fiscal externo e plano observação; Diego aceita menores ajustes visuais ou bloqueia graves; registrar autorização antes deployreal.

**Dependências:** H39, H40, H46, H48, H51, H52, H53. **Referências:** P-02–04, P-08–09, P-14, FR-77. **Gate:** Todos os gates produtivos; Diego.

**Validação exigida:** Ata/checklist com responsável/data/evidências, lojas/horários/contatos e imagens release; sem teste faltante marcado aprovado. Expansão para 15 requer novo teste/aceite.

**Entrega verificável:** código/configuração ou relatório da prova, contratos/migrations afetados, evidências sanitizadas e progresso atualizado. Aplicar os critérios transversais pertinentes; não implementar extras para fechar a história.

## Rastreabilidade e fora do backlog

FR-01–77, RN-01, NFR-01–16 e CA-01–30 permanecem no PRD. A matriz de referências é apoio; nenhuma regra é removida por falta de citação em um parágrafo. P-15 (fiscalfase 2), integração de PDVs, campanhas, estoque, pagamento misto, app nativo e IA não têm história MVP. Criar backlog futuro somente quando solicitado.

Não há obrigação de terminar épicos inteiros antes de outro: por exemplo, PSP sem credenciais bloqueia a prova, mas não cadastro/catálogo. Gates de produção não podem ser dispensados só porque infraestrutura está pronta.
