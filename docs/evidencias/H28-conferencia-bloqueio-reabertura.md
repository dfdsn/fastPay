# H28 — Evidência da conferência, do bloqueio e da reabertura

Registro sanitizado. Claude Code, 07/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V23__fechamento_e_reabertura.sql` | `tab_closing` guarda cada fechamento: quem iniciou (equipe ou acesso do cliente), quem reabriu, quando e o motivo. Há no máximo um em andamento por comanda (índice único parcial) e a linha nunca é apagada. `bill_version` guarda a conta consolidada, imutável, com o total conferido por `CHECK`. RLS: só na comanda ativa, a equipe em nome próprio ou o cliente pelo acesso ativo; reabrir é só da equipe |
| Estado da comanda | `tabs.Tabs` (`OrderTarget.EM_FECHAMENTO`, `startClosingInCurrentUnit`, `reopenInCurrentUnit`, `BillTab.closingId`, `Tab.closingSince`), `JdbcTabStore` | A trava é a mesma de antes. `lockActiveTab` passa a ler a comanda num comando separado, depois da trava: em READ COMMITTED, uma subconsulta dentro do `SELECT … FOR UPDATE` usa a foto anterior à espera e não enxergava o fechamento recém-gravado. O teste de corrida pegou esse caso (pedido e fechamento aceitos juntos) |
| Elegibilidade | `production.Production.pendingOfTabInCurrentUnit` | Conta como pendente toda unidade ativa da comanda que não foi entregue nem cancelada, inclusive a que ainda não tem linha de produção (encaminhamento pendente). Impossível aberto também conta |
| Fechamento | `bills.Closings`, `ClosingController`, `GuestClosingController` | Iniciar: trava a comanda e confere itens, problema em aberto, desconto em revisão e o total visto. Depois grava o fechamento, a versão 1 e a auditoria. Reabrir exige `REABRIR_COMANDA` e motivo, e confere o fechamento que a tela mostrava. Contrato da H30: `currentVersionInCurrentUnit` |
| Travas | `Orders`, `Cancellations`, `Transfers`, `DeliveryCorrections`, `Bills`, `Tabs.linkVisit` | Em fechamento, 409 `COMANDA_EM_FECHAMENTO` (na transferência para uma comanda em fechamento, `DESTINO_EM_FECHAMENTO`). O serviço continua e cada mudança grava uma nova versão |
| Frontend | `bills/bill-panel.ts`, `bills.service.ts`, `guest/guest-bill.ts`, `tabs/tabs-page.ts`, `orders/tab-orders-page.ts`, `production/deliveries-page.ts` | Equipe: seção "Fechamento" com o que impede, "Iniciar fechamento" com o total da tela, conta consolidada com a versão e "Reabrir comanda" com motivo. Cliente: "Fechar a conta (total)". A lista de comandas mostra "em fechamento". O envio recusado em fechamento preserva o rascunho, porque a recusa é definitiva e a chave do envio é descartada |

Nenhuma dependência nova.

## 2. T-03 da H28 (aprovada por Diego em 07/11/2026, cartão "Aprovar como proposto")

| Regra | Valor |
|---|---|
| Quem inicia | A equipe com `OPERAR_COMANDA` ou o cliente pelo acesso da comanda, confirmando o total que vê |
| Quando pode | Todas as unidades entregues ou canceladas, sem problema em aberto e sem desconto em revisão. Comanda sem consumo pode iniciar (a H29 encerra a conta zerada) |
| O que trava | Pedido, cancelamento, transferência de ou para a comanda, desconto, couvert, vínculo de continuação e desfazer entrega |
| O que continua | Serviço (gera nova versão), informar problema, recuperar o acesso |
| Versão | 1 ao iniciar e a seguinte a cada mudança do serviço; a H30 cobra a mais alta |
| Reabrir | `REABRIR_COMANDA` (gerente, administrador, garçom com delegação), motivo de 5 a 300 caracteres, auditoria, mesma configuração capturada. O cliente não desfaz sozinho |
| Contrato H30/H31 | Cobrança pendente ou incerta recusa a reabertura; comanda paga não reabre |

## 3. Testes

| Teste | Critério da H28 |
|---|---|
| `ClosingIntegrationTest.itemNaoEntregueImpedeEFechamentoTravaLancamentosAteReabrir` | **Pronta não entregue** (CA-08): aguardando preparo, pronto e impossível impedem; depois da entrega a elegibilidade é reavaliada. Total malformado 400, total diferente 409 `CONTA_MUDOU`; inicia com a versão 1; iniciar de novo 409. Em fechamento: pedido, cancelamento, transferência de origem e de destino, desconto, desfazer entrega e vínculo de continuação recusam. **Serviço antes da cobrança** (CA-11): o serviço sai e a versão 2 tem o novo total. Reabrir: garçom sem permissão 403, motivo curto 400, fechamento diferente 409, gerente reabre, reabrir de novo 409; auditoria e motivo gravados. **Rascunho preservado**: o mesmo pedido recusado em fechamento é aceito depois de reabrir, com a escolha do serviço mantida. Garçom com a delegação reabre |
| `…clienteConfereRetiraOServicoEIniciaOFechamento` | Sem acesso 404; item pronto impede; problema em aberto impede até ser concluído; o cliente retira o serviço e fecha com o total visto (o fechamento registra o acesso, sem identidade); devolve o serviço em fechamento (versão 2); o contrato da H30 devolve a versão 2 |
| `…descontoEmRevisaoImpedeOFechamento` | Cancelamento real reduz o consumo e o desconto passa da autoridade de quem concedeu: o fechamento é recusado com `DESCONTO_EM_REVISAO` |
| `…envioEFechamentoSimultaneosNuncaDeixamCobrancaDesatualizada` | **Corrida envio × fechamento** (CA-07), 4 rodadas em paralelo: ou o pedido entra e o fechamento é recusado por item pendente, ou o fechamento entra e o envio é recusado; nunca os dois |
| `MigrationsIntegrationTest` | 23 migrations em banco vazio e a partir da V1 |
| `bill-panel.spec.ts` (2 novos) | O que impede aparece e o botão fica desabilitado; inicia com o total da tela, mostra a versão, reabrir exige motivo e envia o fechamento lido |
| `guest-bill.spec.ts` (1 novo) | Item em preparo desabilita "Fechar a conta"; depois de elegível, fecha com o total e mostra a conta fechada |

As corridas de cancelamento e transferência contra o fechamento usam a mesma trava da comanda que a corrida do envio (`Tabs.lockForOrderInCurrentUnit`). Elas foram exercitadas em sequência (recusa em fechamento), sem um teste paralelo próprio.

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `9774f4f` | 482 testes, 0 falhas, cobertura atendida |
| `./scripts/verify.sh frontend` (`npm ci`, lint, `test:ci`, build) | Contêiner `node:24.18.0-trixie-slim` | idem | 38 arquivos, 211 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); celular real, na homologação de Diego. A reabertura com cobrança incerta e a comanda paga dependem da H30/H31: hoje não existe cobrança, então o contrato fica documentado e será testado nelas.
