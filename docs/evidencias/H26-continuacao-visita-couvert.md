# H26 — Evidência da continuação de visita e dos ajustes de couvert

Registro sanitizado. Claude Code, 07/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V22__continuacao_de_visita_e_couvert.sql` | `visit_link` é o fato do vínculo (comanda, anterior, visita de origem e de destino, confirmação, quem, quando), único por comanda, só `SELECT`/`INSERT`. `visit.exit_validated_at`/`exit_tab_id` para a saída validada (H32). `tab.visit_id` passa a ser atualizável (a policy de `UPDATE` já exige comanda ativa e autor). `couvert_charge` guarda cada couvert lançado (automático, manual, correção), no máximo um válido por comanda (índice único parcial); encerrar só preenche `ended_*` (cancelado, devolvido, não repetido), sem `DELETE`. O couvert automático das comandas já abertas vira lançamento com o mesmo valor. `consumer_issue.about_couvert` marca a contestação |
| Vínculo | `tabs.Tabs.linkVisit`/`continuationCandidates`, `JdbcTabStore`, `TabController` | Trava a comanda nova e depois a visita de destino; confere permissão, confirmação, versão, anterior (aberta antes, dentro de 24 h), vínculo único e saída validada; muda a visita, grava o fato e a auditoria e publica `VisitLinked` na mesma transação. A lista de candidatas vem por hora de abertura, sem nome como critério |
| Saída validada (contrato H32) | `Tabs.endVisitByValidatedExitInCurrentUnit` | Encerra a visita; a próxima entrada é visita nova e volta a ter couvert. A saída automática não chama |
| Couvert | `bills.Bills` (`includeCouvert`, `cancelCouvert`, `correctCouvert`, `recordCouvertRefundInCurrentUnit`), `bills.CouvertCharges` (eventos `TabOpened`, `VisitLinked`), `domain.CouvertRules`, `JdbcBillStore` | K = couvert válido da comanda. Toda mudança trava a comanda e depois a visita (mesma ordem do vínculo). Situação da visita: cobrado, dispensado (indevido/isenção), aguardando correção ou sem couvert. No vínculo, o couvert da comanda nova termina como não repetido quando a visita já não está sem couvert |
| Devolução (contrato H35) | `Bills.recordCouvertRefundInCurrentUnit` | Mesmos motivos e efeitos do cancelamento, também em comanda encerrada; devolução livre não chama |
| Contestação | `attendance.ConsumerIssues.report(…, aboutCouvert, …)`, `GuestIssueController`, `JdbcIssueStore` | Problema da conta marcado (sem item); bloqueia a cobrança como qualquer problema aberto; cancelar o couvert não conclui o problema |
| API | Seção 6 da especificação | Códigos e mensagens em português |
| Frontend | `tabs/tabs-page.ts`, `tabs.service.ts`, `bills/bill-panel.ts`, `bills.service.ts`, `guest/guest-bill.ts`, `guest/guest-issues.ts`, `attendance/issues-panel.ts` | "Continuação de visita" na lista de comandas (com a permissão): lista por abertura e confirmação obrigatória; a comanda mostra "continua a visita da comanda N". Conta da equipe: couvert com origem, onde está cobrado na visita, dispensa, correção pendente, histórico e as ações que o servidor permite. Cliente: a situação do couvert e a opção "O couvert" em "Algum problema?" |

Nenhuma dependência nova.

## 2. T-03 da H26 (proposta em 07/11/2026, aguardando Diego)

| Proposta | Valor |
|---|---|
| Quem vincula | `VINCULAR_CONTINUACAO_VISITA`, que já existia: gerente e administrador; garçom só com delegação |
| Como escolhe | Lista das comandas da unidade abertas nas últimas 24 h e até a abertura da nova, ativas ou encerradas, de outra visita e sem saída validada; por hora de abertura, sem sugerir nem filtrar pelo nome; confirmação obrigatória "é a mesma pessoa e ela não saiu" |
| Vínculo | Uma vez por comanda, só se ela ainda está sozinha na visita; sem desfazer. Auditado |
| Fim da visita | A virada do dia e a saída automática não encerram; a saída validada (H32) encerra |
| Couvert no vínculo | Se a visita já tem couvert cobrado, dispensado ou aguardando correção, o couvert da comanda nova sai (não repetido), sem recobrança |
| Inclusão manual | `OPERAR_COMANDA`, comanda aberta no modo manual, valor da configuração vigente na inclusão |
| Cancelamento | `CANCELAR_ITEM`; motivo Indevido, Isenção, Duplicidade ou Correção, e texto de 5 a 300 caracteres. Indevido/isenção dispensam a visita; duplicidade só com outro válido na visita, que continua; correção libera a inclusão de correção |
| Inclusão de correção | `CANCELAR_ITEM`, valor digitado de R$ 0,01 a R$ 200,00 (o intervalo do couvert na configuração, H14) e motivo |
| Contestação | Problema da conta marcado "Couvert" (H21: um problema da conta em aberto por comanda; bloqueia a cobrança) |
| Devolução | Contrato para a H35 com os mesmos motivos; devolução de valor livre não mexe no couvert |

## 3. Testes

| Teste | Critério da H26 |
|---|---|
| `VisitCouvertIntegrationTest.vinculoSoComPermissaoEConfirmacaoENuncaPeloNome` | **Nomes iguais**: três comandas "Ana" em visitas separadas, cada uma com o seu couvert; garçom sem delegação 403 (listar e vincular); lista por hora de abertura com as duas "Ana"; sem confirmação 400; anterior aberta depois, inexistente e a própria 409; versão 409; **segunda comanda**: gerente vincula, couvert automático da nova sai (não repetido), conta mostra a comanda que tem o couvert; auditoria `visita.vinculada`; vínculo repetido 409; terceira comanda na mesma visita sem couvert; garçom com delegação vincula |
| `…viradaDoDiaNaoEncerraESaidaValidadaEncerraAVisita` | **Virada do dia**: duas comandas em dias operacionais diferentes (23 h) vinculadas, sem repetir o couvert; fora do prazo de 24 h não aparece nem vincula; **saída validada** pelo contrato da H32 encerra a visita (uma vez): a anterior some da lista, o vínculo é recusado e a nova entrada tem couvert |
| `…couvertManualCapturaOValorNaInclusaoESemRepetirNaVisita` | Manual: valor vigente na inclusão (mudou depois da abertura), mudança posterior não altera; inclusão repetida 409; comanda da mesma visita não inclui; comanda automática não aceita manual; couvert desligado depois 409 |
| `…cancelarPorIsencaoDispensaAVisitaInteira` | Garçom sem `CANCELAR_ITEM` 403; motivo fora da lista e texto curto 400; couvert diferente do mostrado 409; isenção zera e dispensa a visita, com histórico e autoria; cancelar de novo 409; a comanda vinculada depois não cobra; o cliente vê "dispensado" |
| `…duplicidadePreservaOValidoECorrecaoExigeInclusaoExplicita` | Duplicidade sem outro válido 409; com duas cobranças na visita (montadas no banco, como a de uma comanda já paga antes do vínculo) a duplicada sai e o original continua; **correção sem recobrança implícita**: a visita aguarda, a comanda vinculada depois não cobra o automático, valor fora do intervalo/com 3 casas 400, garçom 403, a inclusão de correção cobra o valor digitado e não se repete em outra comanda |
| `…devolucaoPeloContratoDaH35SegueOMotivo` | **Devolução com motivo distinto simulada pelo contrato**: duplicidade sem duplicata recusada; indevido devolve e dispensa a visita (a continuação não cobra); devolver de novo recusa; correção devolve sem recobrar |
| `…clienteVeOCouvertDaVisitaEContestaPeloProblemaDaConta` | Cliente vê o couvert na primeira comanda e "já cobrado em outra comanda" na segunda; contestação com item 400; contestação marcada aparece para a equipe e bloqueia a cobrança; cancelar o couvert não conclui o problema |
| `…doisVinculosSimultaneosDeixamUmCouvertNaVisita` | Dois vínculos ao mesmo tempo para a mesma visita sem couvert, ambos com couvert automático: os dois passam e a visita fica com um couvert válido (trava da visita) |
| `CouvertRulesTest` | Precedência da situação da visita, duplicidade só em outra comanda, valor da correção (aceitos e recusados) e motivos |
| `MigrationsIntegrationTest` | 22 migrations em banco vazio e a partir da V1 |
| `tabs-page.spec.ts` (2 novos) | Botão só com a permissão; lista, validação local de escolha e confirmação, corpo com versão; a comanda mostra o vínculo e o botão some |
| `bill-panel.spec.ts` (3 novos) | Inclusão manual com o valor informado; comanda que tem o couvert e histórico de não repetido; cancelamento com motivo e texto e inclusão de correção |
| `guest-bill.spec.ts` (1 novo), `guest-issues.spec.ts` (1 novo, 2 ajustados) | Cliente vê "já cobrado em outra comanda"; contesta o couvert sem item, marcado |

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `d364281` | 478 testes, 0 falhas, cobertura atendida |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | Contêiner `node:24.18.0-trixie-slim` | idem | 38 arquivos, 208 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (CI do PR); E2E Playwright (não configurado); celular real na homologação de Diego. A saída validada e a devolução foram exercitadas pelos contratos que a H32 e a H35 vão chamar; duas cobranças válidas na mesma visita foram montadas no banco, porque hoje o vínculo nunca deixa duas (o caso real é a comanda já paga antes do vínculo, H30). Comanda encerrada como anterior ainda não existe (fechamento é da H28/H29); a lista e o vínculo já a aceitam.
