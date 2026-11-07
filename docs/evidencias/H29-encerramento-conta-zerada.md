# H29 — Evidência do encerramento de conta zerada e do abandono vazio

Registro sanitizado. Claude Code, 07/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid` e senhas fictícias.

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Migration | `V25__encerramento_sem_valor.sql` | `tab_closure` guarda o encerramento: uma linha por comanda, imutável, com o tipo (`SEM_VALOR` ou `ABANDONO`), o fechamento e a versão da conta que ele encerra, quem (equipe em nome próprio ou o acesso do cliente) e o motivo, obrigatório só no abandono. A RLS de inserção exige a comanda ativa, o fechamento em andamento e a versão mais alta dele com total zero: o banco não aceita "encerrar sem valor" uma conta com valor. Em `tab`, uma policy restritiva só deixa gravar `closed_at` igual ao do `tab_closure`, e o gatilho `tab_closed_only_access` deixa a comanda encerrada mudar só o `access_until` |
| Encerramento | `tabs.Tabs` (`ClosureKind`, `Closure`, `closeInCurrentUnit`, `closureInCurrentUnit`, `refreshAccessAfterClosureInCurrentUnit`), `JdbcTabStore` | Grava o `tab_closure`, fecha a comanda com acesso avulso por 7 dias (FR-11), audita `comanda.encerrada` e publica a mudança. O número da comanda fica livre para outra |
| Regras | `bills.Closings` (`closeWithoutValue`, `abandon`, `closeWithoutValueAsGuest`), `Bills.Snapshot.hasItems`, `ClosingController`, `GuestClosingController`, `Responses` | Trava a comanda e confere itens pendentes, problema em aberto e desconto em revisão. Aberta, consolida a versão 1 e encerra na mesma transação; em fechamento, encerra a versão mais alta. Total acima de zero: 409 `CONTA_COM_VALOR`. Abandono com unidade ativa: 409 `COMANDA_COM_CONSUMO` |
| Acesso depois do encerramento | `attendance.ConsumerIssues` | Problema informado depois do encerramento suspende a expiração do acesso avulso; a conclusão do último problema dá mais 7 dias a partir dela (FR-11, CA-23) |
| Frontend | `bills/bills.service.ts`, `bills/bill-panel.ts`, `guest/guest-bill.ts` | Cliente: com a conta zerada, "Encerrar sem valor a pagar" no lugar de "Fechar a conta", e depois "Encerrada sem valor a pagar às HH:mm". Equipe: "Encerrar sem valor a pagar" (pelo cliente sem celular) e "Encerrar abandonada" com motivo, e a situação com quem encerrou |

Nenhuma dependência nova. Nenhuma tabela de pagamento, cobrança ou comissão foi criada ou tocada.

## 2. T-03 da H29 (proposta em 07/11/2026, aguardando Diego)

| Regra | Valor proposto |
|---|---|
| Conta zerada (FR-41) | Total R$ 0,00; o cliente pelo próprio acesso ou a equipe com `OPERAR_COMANDA`, sem autorização; aberta ou em fechamento |
| Abandono (FR-42) | Equipe com `OPERAR_COMANDA`, motivo de 5 a 300 caracteres, nenhuma unidade ativa, conta zerada (sem couvert válido) e sem pendência; com valor é a H34 |
| Registro | Tipo, quando, quem e motivo; situação "Encerrada sem valor a pagar" |
| Acesso avulso | 7 dias depois do encerramento; problema aberto depois suspende; a última resolução reinicia |
| Saída | Não liberada nem validada aqui; a H32 usa o modo capturado na abertura |

## 3. Testes

| Teste | Critério da H29 |
|---|---|
| `ZeroClosingIntegrationTest.clienteEncerraContaSemPedidosSemPagamentoEComPrazoDeAcesso` | **Conta sem pedidos** (CA-20): sem acesso 404; o cliente encerra sem autorização; `tab_closure` pelo acesso, sem identidade, aponta a versão 1 com total 0,00; auditoria; `closed_at` e `access_until` = encerramento + 7 dias; encerrar de novo 409; a equipe vê como foi; a comanda sai da lista e recusa pedido; depois de 7 dias o acesso avulso some |
| `…descontoDeCemPorCentoComOuSemServicoEncerraSemValor` | **Desconto de 100% com serviço** (o serviço sobre o consumo após desconto zera junto): antes do desconto 409 `CONTA_COM_VALOR`; abandono com itens 409 `COMANDA_COM_CONSUMO`; a equipe encerra pelo cliente, em nome próprio. Em fechamento com R$ 0,90 recusa. **Sem serviço na unidade**: o cliente inicia o fechamento em 0,00 e encerra a versão mais alta |
| `…couvertEProblemaAbertoImpedemAteSeremResolvidos` | **Couvert** automático é valor a pagar mesmo sem pedido (`CONTA_COM_VALOR`, também no abandono). **Problema aberto** impede mesmo depois do couvert cancelado; concluído, o cliente encerra. Depois do encerramento, problema novo suspende o prazo do acesso e a conclusão dá mais 7 dias a partir dela |
| `…funcionarioEncerraAbandonoVazioComMotivoEAuditoria` | Abandono sem pedido: motivo curto 400, encerra com motivo e auditoria; de novo 409. Item pendente impede; unidade ativa impede; com tudo cancelado, encerra. Produção sem `OPERAR_COMANDA` 403 |
| `…bancoSoAceitaEncerramentoRegistradoEComandaEncerradaSoMudaOPrazo` | Pelo papel da aplicação, `closed_at` sem `tab_closure` é recusado; comanda encerrada recusa mudar `closed_at` e aceita mudar `access_until` |
| `ConsumerIssuesIntegrationTest.problemaDepoisDoEncerramentoPreservaEncerramentoEVaiAoGerente` | Antes simulava a comanda encerrada direto no banco; agora usa o encerramento sem valor real. O encerramento fica como estava e o acesso ganha 7 dias a partir da resolução |
| `MigrationsIntegrationTest` | 25 migrations em banco vazio e a partir da V1 |
| `bill-panel.spec.ts` (2 novos) | Abandono exige motivo, envia o motivo e mostra quem encerrou; a equipe encerra a conta zerada |
| `guest-bill.spec.ts` (1 novo) | Conta zerada troca "Fechar a conta" por "Encerrar sem valor a pagar" e mostra a situação encerrada |

**Pagamento e comissão fictícios:** não existe tabela de pagamento ainda (H30); o encerramento só grava `tab_closure`, a versão zerada da conta e a auditoria, e a RLS recusa versão com valor. **Cobrança incerta:** contrato da H30 (documentado em `Closings.finish`), sem como exercitar hoje.

### Execuções

| Comando | Ambiente | Commit | Resultado |
|---|---|---|---|
| `./mvnw clean verify` (JUnit, Modulith, enforcer, JaCoCo) | Contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker com PostgreSQL 18 (Testcontainers) | árvore de trabalho sobre `develop` `bf915b6` | 509 testes, 0 falhas, cobertura atendida |
| `npm ci`, `npm run lint`, `npm run test:ci`, `npm run build` | Contêiner `node:24.18.0-trixie-slim` | idem | 39 arquivos, 224 testes, lint e build verdes |

Não executados localmente: `compose-smoke.sh` e imagens (rodam na CI do PR); E2E Playwright (não configurado); celular real, na homologação de Diego.
