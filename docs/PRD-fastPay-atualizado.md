---
title: "PRD — fastPay: Comandas, Operação e Pagamentos"
version: "3.0"
updated: "2026-10-06"
status: "Escopo consolidado; dependências de lançamento identificadas"
owner: "Diego"
---

# PRD — fastPay

**Versão 3.0 · Consolidado em 06/10/2026**

Documento de requisitos de produto para orientar desenvolvimento, testes e piloto. Consolida o PRD original, a versão 2.0 e as decisões posteriores da entrevista com Diego até esta consolidação. As alterações de quantidade parcial, produção, infraestrutura e processo de desenvolvimento são refletidas neste pacote. Substitui as regras anteriores conflitantes. Não define stack, arquitetura física ou cronograma fechado.

**Convenções:** requisitos FR e regras RN representam decisões consolidadas. Critérios CA tornam essas decisões verificáveis. Itens P são pendências explícitas: não são funcionalidades aprovadas nem autorização para inventar regras. Nomes de estados são conceituais; a modelagem técnica poderá separar entidades preservando os comportamentos.

## 1. Visão e proposta de valor

O fastPay permite que consumidores acompanhem e paguem suas comandas individuais pelo celular, reduzindo a espera para fechar a conta. O estabelecimento opera comandas, pedidos, produção e atendimento em áreas próprias; o dono acompanha indicadores operacionais e financeiros.

No MVP, o fastPay é a fonte principal das comandas e dos pedidos do piloto. O funcionário abre a comanda, apresenta um QR de acesso e registra o consumo. O cliente acompanha, confere e paga, sem instalação ou cadastro obrigatório.

A visão de rede inclui identidade única opcional do consumidor, histórico entre estabelecimentos e dados comerciais agregados para negociar parcerias e promoções futuras. Fidelidade, carteira e execução de campanhas não integram o MVP. A hipótese de valor da rede ainda deve ser validada; o produto precisa ser útil em um único estabelecimento.

Pagamentos integrados serão processados por PSP, com recebimento destinado ao estabelecimento e comissão do fastPay pelo arranjo contratado. O produto não prevê custódia própria de recursos. Isso é uma decisão operacional, não uma conclusão sobre enquadramento regulatório.

### 1.1 Problemas atendidos

- Espera e dependência da equipe para conferir e pagar a conta.
- Divergências de consumo e falta de rastreabilidade nas correções.
- Falhas de comunicação entre atendimento, cozinha e bar.
- Falta de visibilidade sobre pagamentos, pendências e devoluções.
- Pouca informação sobre demanda, horários de pico e demora operacional.

### 1.2 Limites do produto

Não é um ERP ou sistema de estoque no MVP. Não exige hardware proprietário. A operação requer internet e dispositivos adequados para funcionários e produção; o consumidor sem celular permanece atendido pela equipe. Não promete eliminar qualquer espera causada por banco, produção ou resolução de divergências.

## 2. Objetivos, métricas e piloto

### 2.1 Resultado esperado

Validar o ciclo abrir comanda → pedir → produzir → entregar → conferir → pagar → liberar, reduzindo a espera de fechamento sem aumentar erros ou duplicação de trabalho.

### 2.2 Implantação gradual

1. Piloto acompanhado em **2 ou 3 estabelecimentos**, preferencialmente conhecidos de Diego, inicialmente na região de Alphaville.
2. Estabelecimentos devem aceitar lançamento direto no fastPay, acesso online, acompanhamento e feedback frequente.
3. A operação fiscal externa e a contingência manual precisam ser viáveis nesses locais.
4. Expandir para **15 estabelecimentos** após validar fluxos críticos, capacidade e estabilidade.

A estimativa original de oito meses com três desenvolvedores não está revalidada: houve ampliação do MVP, especialmente em produção, atendimento e indicadores. Reestimar após decomposição do backlog.

### 2.3 Indicadores de sucesso

| Indicador | Diretriz |
|---|---|
| Tempo de fechamento | Buscar redução superior a 50% contra o processo anterior |
| Adoção | Proporção de comandas pagas pelo consumidor no celular; meta numérica pendente |
| Autonomia | Intervenções de suporte por noite e por estabelecimento |
| Operação | Pedidos perdidos, duplicados, correções e esforço de lançamento |
| Pagamentos | Divergências, duplicidades, tempo de confirmação e resolução |
| Rede | Retorno de clientes cadastrados e uso em mais de um estabelecimento |
| Volume | GMV integrado, recebimentos externos e comandas atendidas, separados |

Instrumentar início da conferência/fechamento, geração de cobrança, confirmação e liberação. O instante subjetivo “quero ir embora” exige observação no piloto e não pode ser inferido apenas de um clique. Medir média, mediana e p95 quando aplicável. A meta original de 500 cadastrados permanece referência a revisar, não gate de lançamento.

**Condições de expansão:** validação de fluxos críticos; nenhuma falha grave conhecida de cobrança, perda de pedidos ou isolamento entre estabelecimentos; reconciliação das divergências; teste para a capacidade pretendida. Definir período mínimo de observação e limites quantitativos em P-14.

## 3. Escopo por fase

### 3.1 MVP — fase 1

- Web responsiva/PWA com áreas separadas por perfil.
- Estabelecimentos criados exclusivamente pelo admin fastPay.
- Convites, vínculos multiestabelecimento, permissões e autenticação.
- Comanda individual aberta por funcionário; acesso por QR na tela.
- Cliente avulso ou login Google; histórico pessoal opcional.
- Catálogo simples, disponibilidade e preços fixados no envio.
- Pedidos em rascunho, envio, cozinha/bar e confirmação de entrega.
- Correções, cancelamentos e transferência parcial ou total de quantidades inteiras, preservando a produção de origem.
- Atendimento de problemas por responsável ou central compartilhada.
- Consumo, serviço, couvert e desconto configuráveis.
- Pagamento integral por um meio: Pix/cartão integrado, dinheiro ou maquininha externa.
- Conciliação, pendências, devoluções, duplicidades e acompanhamento de chargeback.
- Saída automática ou validada por funcionário.
- Conta zerada, encerramento com débito e regularização posterior.
- Comprovante não fiscal em PDF e exportações CSV.
- Planos, comissão, mensalidade Pix, suspensão e cancelamento.
- Dashboards por estabelecimento, consolidação do dono e análise agregada da rede.
- Catálogo de referência de produtos e relatórios para negociação comercial.
- Auditoria, suporte temporário de leitura, backups e recuperação.

### 3.2 Fase 2 — compromisso explícito

**Emissão fiscal dentro do fastPay**, em módulo próprio. Antes da implementação serão definidos documentos atendidos, abrangência territorial, integração com provedor ou emissão própria, credenciais e fluxos de emissão, cancelamento, contingência e relação com devoluções. A fase 2 é imediatamente posterior ao MVP; não corresponde à antiga numeração do roadmap.

### 3.3 Evoluções posteriores — sem compromisso de entrega no MVP

Integrações com PDVs e produção de terceiros; outros PSPs; adicionais pagos e combos; desconto por item e promoções automáticas; fidelidade, cupons e campanhas da rede; carteira; pré-autorização/Modo Express; operação offline completa; NFC e catracas; pedido autônomo do cliente; conta coletiva/rateio; pagamento misto e parcelamento; WhatsApp e push; aplicativo nativo; defesa de chargeback dentro do fastPay. O sequenciamento desses itens ainda será definido. Receita de float não é premissa do modelo aprovado.

## 4. Perfis, autenticação e autorização

### 4.1 Áreas de acesso

| Perfil | Responsabilidades |
|---|---|
| Consumidor | Consultar sua comanda, informar problema, conferir, retirar serviço, pagar, baixar comprovante e consultar histórico vinculado |
| Garçom | Abrir comanda, exibir QR, montar/enviar pedido, confirmar entrega, atender chamados e executar permissões delegadas |
| Cozinha/bar | Consultar fila do setor, iniciar/concluir preparo, sinalizar impossibilidade e confirmar ciência de cancelamento; sem visão financeira |
| Gerente | Supervisionar operação, transferir atendimento, delegar permissões operacionais, autorizar devoluções, tratar débitos e corrigir recebimentos externos |
| Admin do estabelecimento | Gerenciar equipe, perfis, catálogo, setores, configurações, conta PSP, assinatura e dashboards; pode exercer gerente |
| Admin fastPay | Criar estabelecimentos, administrar planos/condições, mensalidades, suspensão, catálogo de referência, indicadores da plataforma e suporte autorizado |

**FR-01 — Estabelecimento.** Somente admin fastPay cria nome, e-mail do dono, documento quando informado e ID interno único. Sem CNPJ é permitido preparar cadastro/configuração; não é permissão de operar com dinheiro real. Documento não é credencial. Ativação exclusiva pelo admin fastPay conforme FR-72; validação cadastral/CPF/CNPJ e elegibilidade PSP permanecem em P-02.

**FR-02 — Administrador inicial.** O dono recebe convite e define senha. A partir desse acesso, cria os convites necessários. Não há autocadastro público para assumir função de funcionário.

**FR-03 — Multiestabelecimento.** Uma identidade pode ter vários vínculos, com funções diferentes. Após login, seleciona unidade; com apenas um vínculo, direcionamento automático. Unidade ativa sempre visível. Desativar um vínculo não afeta os demais. Administradores de uma loja não veem os vínculos em outras.

**FR-04 — Credenciais.** Equipe utiliza contas individuais de e-mail e senha, com recuperação por e-mail. O convidante não conhece a senha. Login Google é a forma de autenticação do consumidor cadastrado; avulso permanece disponível se o login falhar ou for recusado. Associação entre identidade de equipe e consumidor com mesmo e-mail exige definição técnica segura, sem fusão implícita de privilégios.

**FR-05 — Dois fatores.** Obrigatório para admin fastPay, admin do estabelecimento e gerente; opcional para garçom/produção. Aplicativo autenticador e códigos de recuperação de uso único. Promoção a perfil privilegiado exige configuração antes de acessar funções. Recuperar senha por e-mail não remove MFA. Recuperação excepcional de MFA: P-07.

**FR-06 — Autorizações.** Verificação no servidor em todas as operações; ocultar menu não substitui autorização. Mudanças de permissão devem surtir efeito nas operações seguintes, inclusive em sessão ativa. Desativação revoga acesso ao estabelecimento e preserva autoria histórica.

### 4.2 Permissões operacionais

| Ação | Garçom | Gerente | Admin estabelecimento |
|---|---|---|---|
| Abrir comanda, enviar pedido e entregar | Sim | Sim | Pode exercer operação |
| Atender problema / concluir como tratado | Atendimento; conclusão conforme permissão delegada | Sim | Sim |
| Cancelar item, conceder desconto, reabrir | Mediante permissão específica | Sim | Sim |
| Transferir unidades entre comandas | Permissão específica | Sim | Sim |
| Desfazer entrega | Permissão específica | Sim | Sim |
| Alterar disponibilidade | Permissão específica | Sim | Sim |
| Registrar recebimento externo | Permissão específica | Sim | Sim |
| Recuperar acesso do consumidor | Funcionário autorizado | Sim | Sim |
| Vincular continuação de visita | Permissão específica | Sim | Sim |
| Autorizar devolução, encerrar com débito, iniciar regularização | Não | Sim | Ao exercer gerente |
| Invalidar recebimento externo | Não | Sim | Ao exercer gerente |
| Conectar/substituir conta PSP | Não | Não | Exclusivo |
| Criar perfis de gerente/admin | Não | Não | Exclusivo no estabelecimento |

Permissões de ajuste financeiro ficam desabilitadas por padrão para garçons. Cancelamentos, descontos, reaberturas e transferências exigem motivo. O gerente só delega permissões operacionais explicitamente delegáveis, dentro de sua autoridade; devoluções e demais ações exclusivas não são delegáveis. Não altera o próprio perfil. O admin pode nomear outro admin; sempre deve restar pelo menos um ativo. Nenhum perfil de estabelecimento concede acesso administrativo ao fastPay.

## 5. Jornadas principais

### UJ-01 — Atendimento e pagamento integrado

Funcionário abre comanda → informa nome/apelido e mesa opcional → apresenta QR → cliente acessa como avulso ou entra com Google → garçom monta e envia pedido → itens seguem ao setor → produção marca pronto → garçom entrega → cliente confere, ajusta opção de serviço e inicia fechamento quando elegível → escolhe Pix/cartão → PSP confirma → comprovante e liberação conforme configuração.

### UJ-02 — Sem celular ou pagamento externo

Funcionário abre e opera comanda → consumo e entrega seguem o mesmo fluxo → funcionário conduz conferência/fechamento → recebe integralmente em dinheiro ou maquininha externa → usuário autorizado registra recebimento e referência aplicável → saída segue modalidade. Declaração do cliente de pagamento não quita a conta.

### UJ-03 — Problema na conta

Cliente seleciona item ou conta e informa motivo → responsável ou central assume → funcionário explica ou executa correção permitida, reabrindo quando necessário → conclui com resolução obrigatória → cliente confere novamente. Após pagamento, a solicitação não desfaz quitação; eventual devolução depende do gerente.

### UJ-04 — Pagamento incerto

Tentativa fica pendente → cliente e equipe visualizam situação → sistema aguarda webhook e consulta PSP → funcionário pode solicitar nova consulta → sem confirmação definitiva, não há segunda cobrança, quitação externa, reabertura ou liberação automática.

### UJ-05 — Entrada de parceiro

Admin fastPay cria estabelecimento e convida dono → dono define credenciais/MFA e equipe → configura catálogo, setores, cobranças e PSP → valida fluxos → ativa para operação, iniciando o ciclo comercial. Ativação é exclusiva do admin fastPay, conforme FR-72; validações cadastrais externas permanecem em P-02.

### UJ-06 — Análise comercial da rede

Admin fastPay consulta demanda agregada e classificação → identifica produtos/períodos relevantes → exporta relatório sem identificação individual → usa dados em negociação. Não há portal de fornecedor ou motor de campanhas no MVP.

## 6. Comandas, acesso e visitas

**FR-07 — Abertura individual.** Apenas funcionário abre. Cada comanda representa uma pessoa; uma mesa pode ter várias. Número automático distinto entre comandas ativas da unidade, nome/apelido e mesa opcional. Nomes repetidos permitidos. Trocar mesa não altera titular ou consumo. Não há abertura pelo QR de mesa.

**FR-08 — Acesso QR.** Funcionário exibe QR exclusivo na tela, sem cartão físico reutilizável. Validade de 2 minutos e uso único; gerar novo invalida anterior. Abrir/visualizar o link não consome; confirmar vínculo consome atomicamente. Nome/número não concedem acesso. QR de vínculo é distinto do QR de saída.

**FR-09 — Sessão e recuperação.** Um único acesso ativo de consumidor por comanda. Funcionário autorizado confere presencialmente o vínculo, gera novo QR e revoga acesso anterior. Recuperação preserva consumo, chamados e pagamentos; não cancela cobranças. Procedimento objetivo de verificação em P-07.

**FR-10 — Cadastro/histórico.** Cadastro opcional via Google, válido na rede. Vincular comanda requer acesso válido e confirmação do consumidor, inclusive no final do atendimento. Não associar históricos por coincidência de nome/número. Cada estabelecimento só acessa suas operações.

**FR-11 — Consulta posterior.** Avulso mantém acesso por sete dias após quitação ou encerramento zerado. Débito, pagamento incerto, devolução em andamento ou problema aberto suspendem a expiração; após última resolução, novo prazo de sete dias. Revogação de segurança prevalece. Cadastro vinculado mantém histórico conforme política de retenção a definir; não implica acesso eterno.

**FR-12 — Visita e couvert.** Funcionário com permissão pode vincular nova comanda à anterior do mesmo consumidor/unidade mediante confirmação de permanência, nunca por apelido apenas. Couvert válido na visita não se repete. Devolução por cobrança indevida/isenção mantém dispensa naquela visita; por duplicidade preserva cobrança válida original; por correção exige inclusão explícita do valor correto, sem recobrança automática integral. Devolução livre sem vínculo não presume cancelamento de couvert. Virada do dia e liberação automática não encerram visita; saída validada implica nova visita na próxima entrada. Auditar vínculo, preservando serviço por comanda e preços por envio.

**RN-01 — Item compartilhado.** Uma unidade inteira de produto pertence à comanda indicada; sem fracionamento do valor da mesma unidade, conta coletiva, rateio monetário ou pagamento misto. Uma linha com múltiplas unidades pode distribuir unidades inteiras por transferência entre comandas conforme FR-17, conservando uma única produção de origem.

## 7. Catálogo e pedidos

**FR-13 — Produto.** Nome, categoria, preço, disponibilidade e destino de preparo, ou dispensa de produção. Descrição/foto opcionais. Tamanhos e versões são produtos distintos. Observações não geram cobrança. Sem estoque quantitativo, adicionais pagos ou combos configuráveis.

**FR-14 — Disponibilidade.** Funcionário autorizado marca disponível/esgotado. Verificar no envio; se houver indisponível, preservar pedido inteiro em rascunho, sem envio parcial automático ou consumo parcial. Pedidos anteriores não são cancelados automaticamente.

**FR-15 — Rascunho e envio.** Garçom monta, observa e confirma “Enviar pedido”. Antes disso, sem consumo e sem produção. No envio, verificar comanda aberta e disponibilidade/preços, registrar consumo e garantir encaminhamento aos setores. Repetição por clique ou reconexão não duplica. Falha de encaminhamento deve ser visível e reprocessável sem outro lançamento. Comanda bloqueada impede envio, preservando rascunho.

**FR-16 — Preço.** Preço vigente no envio, registrado no item. Mudança durante rascunho exige reconfirmação. Alteração no catálogo não muda lançamentos anteriores. Mesmo produto pode aparecer com preços distintos na conta, discriminados. Não fixar catálogo na abertura da comanda. Transferência preserva preço original. Correções não editam silenciosamente preço já enviado.

**FR-17 — Transferência.** Transferir quantidade inteira parcial ou total de uma linha entre comandas abertas da mesma unidade e sem bloqueio de fechamento, com permissão e motivo. Preservar preço, observações, linha original, produção/entrega e histórico; dividir apenas alocações de consumo, sem recriar produção. Atualizar destinos e recalcular ambas as contas atomicamente. Problema aberto de item bloqueia transferência daquele item; problema de conta bloqueia entradas/saídas da comanda. Revisão de desconto afetado bloqueia cobrança, não a correção legítima. Uma unidade não é fracionada monetariamente. Esta regra substitui a proibição de transferência parcial da versão 2.0.

## 8. Produção e entrega

**FR-18 — Setores.** Encaminhamento por produto a cozinha/bar ou outro setor cadastrado. Produção visualiza comanda, mesa, itens, quantidades, observações e responsável, sem dados financeiros do cliente.

**FR-19 — Fluxo por linha.** Aguardando preparo → Em preparo → Pronto → Entregue. Uma sequência por linha enviada, abrangendo toda quantidade remanescente, sem entrega parcial dessa linha. Produção inicia/marca pronto; garçom confirma entrega de todos os destinos ativos apresentados por comanda/nome/mesa. Mudança concorrente de alocação exige atualizar/reconfirmar. Se precisar entregas independentes, separar linhas antes do envio. Sem preparo entra pronto. Atualização em lote tem resultado individual e sucesso parcial, conforme FR-69. Tempos e responsáveis registrados.

**FR-20 — Cancelamento.** Autorizado cancela quantidade inteira parcial ou total em qualquer etapa, com motivo. Mostrar quantidade original/cancelada/remanescente; restante continua na mesma sequência. Antes do preparo ajustar fila; em preparo/pronto alertar e exigir ciência da produção, sem condicionar correção financeira à ciência. Cancelamento total encerra a linha. Após entrega manter histórico e registrar correção financeira. Não apagar item; respeitar reabertura e devolução quando pago. Cancelar não conclui automaticamente um problema aberto.

**FR-21 — Impossibilidade.** Produção sinaliza “Não é possível atender”, com motivo. Impede avanço para pronto/entregue e bloqueia fechamento até resolução. Notificar responsável/central. Produção não cancela valores; autorizado cancela pelo fluxo normal. Substituto exige novo pedido a preço vigente, vinculado à ocorrência.

**FR-22 — Atrasos.** Exibir tempo desde envio e na etapa atual. Limite configurável por setor e outro para problemas de conta; ultrapassagem gera destaque visual, sem cancelamento/conclusão automática. Prazos por produto ficam fora do MVP.

## 9. Atendimento e notificações

**FR-23 — Modalidade por estabelecimento.** (a) Garçom responsável: quem abre assume inicialmente, gerente transfere e outros ajudam conforme permissões. (b) Central: fila compartilhada, funcionário assume de forma exclusiva e dá baixa conforme permissão. Gerente reatribui e acompanha. Modalidade capturada na abertura da comanda; mudança vale só para novas comandas, sem migração silenciosa das pendências existentes.

**FR-24 — Problemas.** Cliente seleciona item ou conta e descreve motivo. Estados Aberta → Em atendimento → Tratada. Identificação de comanda/mesa, horários, responsável e resolução obrigatória. Marcar tratada não altera valores. Correção deve estar concluída antes da baixa; se exige autorização ausente, encaminhar. Com problema aberto antes do pagamento, bloquear nova cobrança. Cobrança já existente deve ser verificada, não cancelada implicitamente.

**FR-25 — Pós-pagamento.** Permitir problema enquanto acesso válido. Preservar encerramento, pagamento e saída; gerente autoriza eventual devolução. Continuidade de acesso avulso segue FR-11.

**FR-26 — Tipos de pendência.** Central/responsável recebe problemas na conta, itens prontos e impossibilidade de atendimento. Entrega conclui aviso de item pronto. Alertas financeiros seguem responsáveis financeiros. Não há botão “Chamar atendimento”; ajuda geral é solicitada presencialmente.

**FR-27 — Canais.** Avisos e contadores dentro da aplicação; produção com sinal visual e som opcional. E-mail para convites, recuperação, mensalidades e avisos administrativos. Sem WhatsApp/push de segundo plano. Recuperar pendências ao retornar/reconectar; ler notificação não conclui tarefa. Registrar falhas de e-mail e permitir novas tentativas. Equipe deve manter aplicação aberta.

## 10. Composição da conta

### 10.1 Configurações

**FR-28 — Serviço.** Por estabelecimento: habilitar, percentual, base antes/depois de descontos e inclusão/exclusão do couvert. Padrão: consumo após desconto, sem couvert. Configuração capturada na abertura; valor acompanha consumo. Na conferência aparece incluído quando habilitado, mas cliente pode retirar sem autorização/reabertura de itens, antes da cobrança. Com cobrança existente, alteração exige sua resolução/invalidação.

**FR-29 — Couvert.** Valor por pessoa, automático ou manual. Automático captura na abertura; manual na inclusão. Uma cobrança válida por comanda, sem repetição na mesma visita. Mudança de configuração não altera existente. Contestação pelo cliente; autorizado cancela com motivo. Devoluções seguem motivo e vínculo de FR-12/FR-71; valor livre não presume estorno de couvert.

**FR-30 — Desconto.** Um por comanda, fixo em reais ou percentual sobre consumo, nunca sobre couvert. Estabelecimento escolhe formatos e limite por funcionário (percentual do subtotal, inclusive para desconto em reais). Alteração substitui anterior. Não superar consumo; motivo e autoria obrigatórios. Comanda em fechamento requer reabertura. Sem desconto por item/promoções automáticas.

### 10.2 Fórmula e apresentação

- C = soma dos lançamentos ativos de consumo.
- D = desconto autorizado sobre C, com 0 ≤ D ≤ C.
- K = couvert ativo.
- B = C ou (C − D), conforme regra do serviço; acrescentar K apenas se configurado.
- S = serviço calculado sobre B, ou zero quando desabilitado/retirado.
- **Total = C − D + K + S.**

Exibir consumo, desconto, couvert, serviço e total separadamente. Valores monetários com centavos. Arredondamento HALF_UP em duas casas e desconto fixo após redução seguem FR-68; nenhuma operação pode produzir total negativo.

## 11. Fechamento, pagamento e saída

**FR-31 — Elegibilidade.** Fechamento exige todos os itens enviados entregues ou cancelados. Aguardando, em preparo, pronto ou impossível não resolvido impedem fechamento. Rascunhos não compõem consumo nem bloqueiam, mas não podem ser enviados após bloqueio. Verificação transacional evita corrida entre pedido e fechamento.

**FR-32 — Bloqueio.** Iniciar fechamento consolida consumo e impede inclusões/cancelamentos/descontos/transferências. Lançamento concorrente deve entrar antes da consolidação ou ser rejeitado. Opção de serviço é ajustável na conferência antes de criar cobrança. Toda cobrança referencia versão e total definido da conta.

**FR-33 — Reabertura.** Somente autorizado, com motivo. Resolver e cancelar/invalidar cobranças anteriores antes. Resultado incerto mantém bloqueio. Comanda paga não reabre para consumo; criar outra. Reabertura para correção não muda configuração de serviço capturada originalmente.

**FR-34 — Meios.** Pix dinâmico e cartão pelo PSP; dinheiro e maquininha externa. Uma quitação integral por um único meio. Crédito em uma parcela; débito condicionado à integração. Sem misto/parcelamento. Trocar meio somente após situação definitiva da tentativa anterior sem pagamento aprovado. Expiração local de tela não comprova expiração no PSP.

**FR-35 — Confirmação integrada.** Apenas PSP confirma, via notificação validada ou consulta. Validar identidade da transação, recebedor, moeda e valor esperado. Comprovante do cliente não autoriza aprovação manual. Nenhum funcionário, gerente ou suporte pode converter tentativa integrada em aprovada por declaração.

**FR-36 — Incerteza.** Exibir aguardando confirmação; consultar PSP além de webhook. Funcionário solicita nova consulta, sem alterar status. Não liberar nova cobrança, quitação externa, reabertura ou saída automática enquanto incerto. Notificar gerente se não resolvido; timeout não equivale a recusa.

**FR-37 — Recebimento externo.** Usuário com permissão registra responsável, data/hora, valor e meio; maquininha com referência do comprovante. Cliente indicar dinheiro não quita. Classificar como registro externo, separado da confirmação PSP. Sujeito às mesmas restrições de pendência e pagamento integral.

**FR-38 — Correção externa.** Exclusiva do gerente, com motivo e registro original preservado. Invalidação não movimenta dinheiro; retorna pendência sem novos consumos. Revogar liberação não usada; preservar saída já validada e alertar. Se dinheiro realmente recebido e devolvido, usar devolução. Registro com devolução vinculada exige análise e não pode ser invalidado cegamente.

**FR-39 — Duplicidade.** Mesmo evento repetido não gera efeito adicional. Dois pagamentos distintos para a mesma quitação geram ocorrência de excedente, não outra venda. Gerente revisa e autoriza devolução; acompanhar resultado. Não devolver automaticamente. Tratamento da comissão do excedente deve ser conciliado sem duplicar receita de venda.

**FR-40 — Saída.** Modalidade configurável: automática após quitação ou validação por funcionário. Automática é autorização, não prova de saída física. Na validada, QR específico ou consulta da comanda no painel, verificando situação atual no servidor. Registrar funcionário/data/hora; releitura informa já validada. Print/tela verde isolados não bastam. Sem celular, equipe consulta painel. Conta zerada segue mesma modalidade. Sem catracas no MVP.

**FR-41 — Conta zerada.** Consumidor pode encerrar sem aprovação, PSP ou comissão. Exigir ausência de itens pendentes, problemas abertos e cobranças pendentes/incertas. Estado apresentado: “Encerrada sem valor a pagar”; não simular pagamento. Permissões para gerar descontos/cancelamentos continuam obrigatórias.

**FR-42 — Abandono/débito.** Sem consumo/pendências, funcionário encerra com auditoria. Com valor não pago, só gerente encerra com débito, motivo e valor, preservando consumo e pendência. Não gera pagamento/comissão/liberação como quitada. Resolver cobranças incertas e tratar produção remanescente antes.

**FR-43 — Regularização.** Gerente inicia quitação integral posterior pelo meio aprovado. Confirmação normal; identifica débito regularizado, sem novos consumos. Recebimento no dia efetivo; integrado gera comissão nas condições vigentes do novo pagamento. Sem cobrança automática ou parcelamento de dívida.

## 12. Devoluções, contestações e PSP

**FR-44 — Devoluções.** Total/parcial, sempre autorizada por gerente, com motivo, valor e pagamento de origem. Não ultrapassar pago menos devoluções concluídas e reservas em processamento. Repetições não duplicam devolução. Solicitação não significa conclusão.

Estados: aguardando autorização → em processamento → concluída ou falhou. Resultado incerto deve continuar reservado/em verificação, não liberar saldo para tentativa duplicada. Integrados dependem PSP; externos dependem devolução pelo meio correspondente e registro auditado/comprovação por autorizado. Preservar consumo e pagamento originais; não reabrir comanda.

**FR-45 — Chargeback.** Registrar dados recebidos do PSP, alertar gerente/dono, exibir situação e impacto separado de devolução. Defesa/documentos no portal PSP. Não cobrar novamente nem reabrir comanda. Abertura da contestação não reverte comissão automaticamente; resultado confirmado orienta ajuste. Política final de perdas/comissões: P-04.

**FR-46 — Primeira integração.** Mercado Pago é primeira opção, condicionada à prova técnica/comercial. Um PSP no MVP. Separar domínio e adaptador; novo PSP exige implementação/homologação, não simples troca de configuração.

**FR-47 — Conta recebedora.** Somente admin estabelecimento conecta/substitui, com reautenticação e identificação visível do recebedor. Auditar. Admin fastPay acompanha saúde, mas não troca destino sozinho. Mudança vale para cobranças novas; anteriores mantêm conta original. Revogação de credencial antiga pode impedir devolução/consulta: exibir pendência de regularização. Conexão inválida bloqueia novas cobranças integradas; externo disponível somente sem tentativa pendente/incerta.

**FR-48 — Conciliação.** Confrontar pagamentos, comissões, tarifas, devoluções e dados disponíveis no PSP; divergências visíveis com referência/situação. Aprovação, liquidação e saldo disponível são distintos. Webhook imediato e consultas de pendência: primeira após 15 segundos, progressão até intervalo máximo de 5 minutos e alerta aos 15 minutos, sem encerrar incerteza. Varredura diária de recentes e todas as pendências antigas; janela e rate limits conforme prova P-01. Consulta manual autorizada é limitada/deduplicada; ninguém aprova integrado por declaração.

Não armazenar PAN completo ou código de segurança. Dados de cartão seguem fluxo seguro do PSP. Credenciais protegidas e nunca apresentadas em relatórios, logs ou suporte.

## 13. Monetização e assinatura

### 13.1 Planos iniciais

| Plano | Mensalidade por estabelecimento | Comissão integrada |
|---|---:|---:|
| Inicial | R$ 100 | 1% |
| Pro | R$ 200 | 0,6% |
| Premium | R$ 400 | 0,3% |

**FR-49 — Gestão comercial.** Admin fastPay gerencia planos e condições negociadas por estabelecimento. Mesmas funcionalidades no MVP. Registrar vigência e histórico; mudança de tabela não altera contratos existentes automaticamente. Dono consulta condições; gerente não gerencia assinatura.

**FR-50 — Comissão.** Somente pagamentos integrados aprovados. Base: total efetivamente pago após desconto, incluindo serviço/couvert, antes de tarifa PSP. Registrar base, percentual e centavos. Externo não gera comissão. Recusa/cancelamento sem recebimento não gera comissão. Alteração de plano não recalcula operações antigas.

**FR-51 — Reversão.** Devolução confirmada reverte proporcionalmente comissão original; integral reverte exatamente valor original, mesmo em partes. Pendente/falha não conclui reversão. Tarifas PSP têm tratamento próprio conforme contrato, sem promessa de devolução. Mecanismo técnico do split precisa suportar ou conciliar a regra comercial.

**FR-52 — Mensalidade.** Cobrança Pix destinada ao fastPay, distinta das comandas. Confirmação PSP; estados pendente, paga, vencida, cancelada. Evitar duas cobranças da mesma competência. Painel do dono e avisos de vencimento/atraso; sem recorrência cartão.

**FR-53 — Ciclo.** Cobrança antecipada desde ativação operacional; configuração sem mensalidade. Data de ativação ancora vencimentos. Se dia não existir, último dia do mês, sem perder âncora original. Novas condições de mensalidade e percentual entram juntas no próximo ciclo, sem proporcional no MVP. Pagamentos antigos mantêm condição original.

**FR-54 — Inadimplência/suspensão.** Vencimento avisa, sem suspensão automática no piloto. Admin fastPay programa com motivo/data/comunicação; bloqueia novas comandas, preserva conclusão/finanças/histórico. Suspensão por inadimplência pausa geração de novos ciclos, não perdoa anteriores. Suspensão administrativa explicita manter ou pausar cobrança com motivo/aviso. Reativação e ciclo seguem FR-73.

**FR-55 — Cancelamento da assinatura.** Dono solicita no painel e confirma data efetiva no fim do ciclo. Não gerar próximas mensalidades. Até lá opera salvo suspensão. Depois, bloquear novas comandas e preservar conclusão/pêndencias/histórico. Não apagar registros nem perdoar valores devidos. Retenção após cancelamento: P-08.

## 14. Relatórios e inteligência comercial

**FR-56 — Financeiro por unidade.** Gerente/dono consultam consumo, desconto, couvert, serviço, recebimentos por meio, pendências, duplicidades, devoluções, comissões, tarifas disponíveis e divergências. Filtro por período e CSV. Distinguir aprovado, liquidado e disponível; não inferir dados ausentes do PSP. Mensalidades/condições restritas ao dono/admin estabelecimento.

**FR-57 — Dia operacional.** Corte e fuso configuráveis por unidade. Exemplo: corte 06 h inclui madrugada seguinte no dia anterior. Não fecha comandas. Mudança com vigência futura preserva classificação antiga. Recebimentos pelo instante efetivo, devoluções pela conclusão. Eventos tardios preservam instante de origem e registro de processamento; conciliação pode atualizar relatório, sem mudar o corte histórico.

**FR-58 — Operacional.** Filtros por período/setor; tempo envio→pronto, pronto→entrega, envio→entrega, resolução de problemas, dias/horários de pico, dias/horários de maior demora, média e p95. Pendentes com espera atual separados de concluídos. Limites de atraso visuais não concluem tarefas. Volume e demora são indicadores distintos.

**FR-59 — Produtos.** Ranking por quantidade e valor, cancelamentos separados; cancelados excluídos das quantidades vendidas. Transferências não duplicam. Devolução monetária não implica quantidade física devolvida sem vínculo específico; política analítica detalhada em FR-75; reclassificação histórica permanece em P-11.

**FR-60 — Dono multiunidade.** Dashboard consolidado das unidades com permissão administrativa ativa; filtrar/comparar vendas, recebimentos, movimento, tempos e produtos. Operação permanece na unidade selecionada. Não somar médias/p95 das lojas como se fossem métricas globais; agregação deve partir de medidas adequadas. Produtos homônimos não são unidos automaticamente.

**FR-61 — Comercial fastPay.** Visão agregada da rede: produto/categoria, quantidade e valor, dias da semana/datas/horas de maior venda, evolução e número de estabelecimentos que comercializam. Considerar todos os meios, inclusive externos, sem gerar comissão sobre eles. Separar demanda atendida (entregue não cancelado), quitadas, débito e ajustes. Demanda usa data do pedido/corte local; recebimentos usam data do pagamento. Sem identificação do consumidor.

**FR-62 — Referência da rede.** Admin fastPay mantém categoria, marca, produto e apresentação. Vínculo opcional no catálogo local; não altera nome/preço/operação. Sem vínculo fica não classificado; apresentar cobertura do ranking. Pratos próprios podem ser comparados por categoria sem presumir receitas equivalentes.

**FR-63 — Relatório de parceria.** Exportar dados comerciais agregados, sem nome de consumidor, identificação de loja, preços ou faturamento individuais. Sem portal de fornecedor. Recortes com risco de inferência de loja devem ser agrupados/omitidos; exportação auditada. Critérios concretos de proteção em P-09. No piloto com poucas lojas, exportação pode não ter recortes seguros suficientes; não relaxar a regra silenciosamente.

## 15. Documentos, suporte e limites fiscais

**FR-64 — PDF não fiscal.** Download para cadastrado/avulso com acesso válido. Estabelecimento, comanda, itens, descontos, couvert, serviço, total, situação e dados de pagamento, instante de emissão. Devolução posterior em nova emissão, histórico preservado. Conta zerada tem demonstrativo, não pagamento fictício. QR de saída separado. Sem envio automático WhatsApp/e-mail.

**FR-65 — Fiscal.** No MVP, emissão fiscal é feita pelo estabelecimento em solução externa, apoiada por consulta/exportação. Comprovante fastPay deve indicar não fiscal. A emissão fiscal integra explicitamente a fase 2. Obrigações fiscais do próprio fastPay sobre mensalidade/comissão precisam de procedimento externo validado em P-03.

**FR-66 — Suporte.** Detalhes operacionais de loja exigem autorização temporária do dono, com motivo, prazo e identidade de suporte, somente leitura no MVP. Auditar concessão, consultas e encerramento; respeitar expiração/revogação. Não assumir identidade de funcionário nem executar descontos/cancelamentos/devoluções. Gestão de planos, faturamento e indicadores agregados tem escopo próprio, sem conceder leitura irrestrita de comandas.

## 16. Estados e invariantes

Não implementar um único status para comanda, cobrança, preparo e visita.

| Domínio | Estados/situações conceituais |
|---|---|
| Atendimento da comanda | Aberta, em fechamento, encerrada |
| Situação financeira | Sem pagamento, pendente/incerta, quitada, sem valor, encerrada com débito, débito regularizado |
| Tentativa integrada | Criada, pendente/em verificação, aprovada, recusada, cancelada, expirada conforme PSP |
| Recebimento externo | Registrado, invalidado com auditoria |
| Produção | Aguardando preparo, em preparo, pronto, entregue; impossibilidade/cancelamento com histórico |
| Problema | Aberto, em atendimento, tratado |
| Devolução | Aguardando autorização, em processamento/em verificação, concluída, falhou |
| Saída | Aguardando quitação, liberada automaticamente, aguardando validação, validada; revogação quando aplicável |
| Assinatura/operação | Em configuração, ativa, suspensão programada, suspensa, cancelamento programado, cancelada |

**Invariantes obrigatórios:**

1. Uma ação repetida não duplica pedido, pagamento, devolução, comissão ou mensalidade.
2. Uma comanda tem um consumidor e pertence a uma unidade; cada unidade ativa de consumo pertence a uma comanda. Alocações podem dividir a quantidade de uma linha original, sem duplicar sua produção.
3. Valor cobrado corresponde à versão confirmada da conta; navegador não decide total financeiro.
4. Situação incerta não equivale a falha nem permite cobrança concorrente.
5. Histórico pago/entregue não é apagado para representar cancelamento ou devolução.
6. Conta zerada não cria pagamento; débito não é receita recebida.
7. Bloqueio de consumo não impede atualizações legítimas de pagamento, produção e auditoria.
8. Cancelamento de item após preparo não apaga o trabalho executado; produção confirma ciência.
9. Nenhum usuário atua em unidade sem vínculo/permissão vigente; admin plataforma não é gerente universal.
10. Devoluções e correções não ultrapassam saldos nem liberam reservas de resultado incerto.
11. Uma saída histórica validada não é apagada por ajuste financeiro posterior.
12. Cobertura do catálogo e limites de agregação devem acompanhar interpretações comerciais.

## 17. Requisitos não funcionais

| ID | Requisito |
|---|---|
| NFR-01 | Web responsiva/PWA, áreas distintas e uso sem instalação obrigatória |
| NFR-02 | Isolamento por estabelecimento e autorização no servidor, inclusive arquivos/exportações e eventos em tempo real |
| NFR-03 | Idempotência, recuperação de falhas e trilha de auditoria em operações críticas |
| NFR-04 | Credenciais protegidas, dados sensíveis fora dos logs e cartão tratado pelo PSP |
| NFR-05 | Abrir/consultar comanda e enviar pedido em até 2 s no p95, sob carga validada |
| NFR-06 | Pedido confirmado aparecer na produção em até 3 s; medir envio e recebimento |
| NFR-07 | Confirmação recebida do PSP refletida na tela em até 5 s; separar demora externa |
| NFR-08 | Dashboard em até 5 s, com última atualização visível |
| NFR-09 | Piloto: 3 unidades simultâneas, cada uma até 250 comandas abertas e 30 tentativas de pagamento/minuto, junto com operação do salão/produção |
| NFR-10 | Revalidar capacidade para 15 unidades antes de expansão; valores são referência de teste, não limite de plano |
| NFR-11 | Operação online; rascunhos preservados localmente e envio explicitamente confirmado; não tratar cache como estado atual |
| NFR-12 | Backups e recuperação a ponto no tempo: RPO até 5 min e RTO até 2 h em desastre, comprovados por restauração |
| NFR-13 | Após restauração, conciliar PSP e revisar lacunas de pedidos/externos antes de ações potencialmente duplicadas |
| NFR-14 | Auditabilidade: ator, unidade, data/hora, motivo, referências e antes/depois quando aplicável |
| NFR-15 | Minimização de dados, política de retenção e tratamento de solicitações de privacidade antes de produção |
| NFR-16 | Sem hardware proprietário obrigatório; dispositivos e conectividade são pré-requisitos do piloto |

Percentis/janelas exatas de NFR-06/07/08, disponibilidade mensal, acessibilidade e navegadores suportados ainda precisam de especificação técnica (P-12). Não converter metas de desastre em tolerância a perda normal de operações.

### 17.1 Contingência online

Sinalizar desconexão e dados desatualizados. Preservar rascunho, mas não prometer pedido enviado. Reconciliar resultado de envio interrompido antes de repetir. Sem aprovação offline de pagamento/saída. Estabelecimento usa procedimento manual de contingência e regulariza após conferência. Pedido já produzido/entregue na contingência não pode ser reenviado à cozinha na regularização. Tela/procedimento e permissões dessa regularização: P-06.

## 18. Critérios de aceitação transversais

| ID | Cenário e resultado esperado |
|---|---|
| CA-01 | Consumidor tenta abrir comanda pelo QR de mesa: não cria nem acessa comanda alheia |
| CA-02 | Funcionário abre comanda e QR é usado: acesso individual; recuperação revoga sessão anterior sem alterar pagamento |
| CA-03 | Usuário muda unidade: consultas e ações limitadas ao novo vínculo; IDs de outra loja são recusados |
| CA-04 | Garçom sem permissão tenta cancelar/descontar: recusa no servidor, mesmo por chamada direta |
| CA-05 | Rascunho com preço alterado/esgotado: exige revisão; nada é cobrado ou enviado parcialmente |
| CA-06 | Mesmo pedido reenviado após timeout: um consumo e uma produção, com recuperação de situação |
| CA-07 | Pedido e fechamento simultâneos: item entra antes da consolidação ou envio é impedido, nunca cobrança desatualizada |
| CA-08 | Item pronto não entregue: fechamento bloqueado; após entrega/cancelamento, elegibilidade reavaliada |
| CA-09 | Cancelamento em preparo: conta corrigida por autorizado e alerta de ciência pendente, sem apagar histórico |
| CA-10 | Transferência de parte ou toda quantidade em unidades inteiras: alocações mudam atomicamente, preço/preparo únicos preservados, fechamento considera cada destino e ranking não duplica |
| CA-11 | Cliente retira serviço antes da cobrança: total recalculado; itens continuam bloqueados |
| CA-12 | Mudança de serviço após abertura: regra capturada permanece; novos produtos usam preço no envio |
| CA-13 | Continuação de visita com couvert já cobrado: não repetir; saída validada anterior impede tratar nova entrada como continuação |
| CA-14 | Problema aberto: nova cobrança bloqueada; concluir exige resolução registrada e correções prévias |
| CA-15 | PSP demora: não aceitar aprovação manual, segundo meio ou nova cobrança; consulta recupera confirmação |
| CA-16 | Mesmo webhook repetido/fora de ordem: não duplica efeitos nem regride situação definitiva indevidamente |
| CA-17 | Dois pagamentos distintos: ocorrência de excedente, comanda quitada uma vez e devolução depende gerente |
| CA-18 | Devoluções parciais repetidas: limite de saldo e reservas; total integral reverte comissão original exata |
| CA-19 | Externo invalidado: restaura débito sem apagar registro/saída passada e sem movimentar dinheiro |
| CA-20 | Conta zerada elegível: consumidor encerra sem autorização, pagamento fictício ou comissão |
| CA-21 | Débito regularizado: histórico original mantido, recebimento no dia efetivo e sem novos consumos |
| CA-22 | QR de saída relido: informar validação anterior; tela estática isolada não autoriza |
| CA-23 | Avulso com chamado aberto no dia 7: não expirar por conclusão temporal; resolução reinicia prazo aprovado |
| CA-24 | Suspensão/cancelamento: bloqueia novas comandas, mas permite finalizar existentes e tratar finanças |
| CA-25 | Mensalidade repetida por retry: uma competência cobrada; alteração de plano só no ciclo seguinte |
| CA-26 | Corte diário muda: dados anteriores conservam classificação e comanda aberta não é fechada |
| CA-27 | Relatório comercial: sem dados pessoais/lojas identificadas; recorte inseguro suprimido e cobertura informada |
| CA-28 | Suporte sem autorização/expirado: recusa; autorizado apenas consulta, com autoria própria auditada |
| CA-29 | Restauração testada: medir RPO/RTO e reconciliar pagamentos antes de novas tentativas |
| CA-30 | Carga combinada do piloto: medir metas, ausência de perdas/duplicidades e isolamento |

Critérios dependentes de regra P devem ser completados antes da implementação correspondente. Testes financeiros devem incluir webhook perdido, duplicado, tardio, timeout de criação e retorno de devolução incerto.

## 19. Pendências e validações obrigatórias

O registro operacional completo, responsável, gate e histórias afetadas está em [decisoes-pendentes.md](decisoes-pendentes.md). Os IDs anteriores são preservados; pendência bloqueia apenas o fluxo afetado e sua liberação produtiva.

| ID | Situação nesta versão |
|---|---|
| P-01 | Aberta: prova técnica/comercial PSP, cobertura, taxas e limitações |
| P-02 | Parcial: cadastro em configuração e ativador definidos; elegibilidade/documentação produtiva ainda aberta |
| P-03 | Aberta: validação regulatória/contratual/fiscal externa |
| P-04 | Aberta: perdas/tarifas/chargeback e efeito final na comissão |
| P-05 | Resolvida para fluxo padrão: suspensão/reativação e desfazer cancelamento em FR-73 |
| P-06 | Parcial: conciliação/suporte definidos; regularização manual de contingência e contatos/horários concretos pendentes |
| P-07 | Parcial: TTL/sessões/MFA/códigos definidos na especificação; senha/prova de vínculo/recuperação excepcional pendentes |
| P-08 | Parcial: fluxo de exclusão solicitado definido; matriz de retenção, anonimização/exportação pessoal e backups pendentes |
| P-09 | Aberta: mínimos de agregação, dominância e proteção contra inferência para parceiros |
| P-10 | Resolvida: precisão, desconto, couvert, chamados/transferência e configuração capturada |
| P-11 | Parcial: dicionário analítico definido; efeito histórico de reclassificação da rede pendente |
| P-12 | Parcial: monitoramento proposto; disponibilidade, janelas/percentis, browsers/acessibilidade e carga detalhada pendentes |
| P-13 | Aberta: dados mínimos e associação segura Google/equipe sem fusão por e-mail |
| P-14 | Parcial: gate de liberação aprovado; janela e metas quantitativas do piloto/expansão pendentes |
| P-15 | Aberta para fase 2: abrangência e implementação fiscal |

**Tratamento do documento original:** afirmações sobre concorrência, custo zero de Pix ou enquadramento regulatório não são consideradas validadas. Escolher PSP não garante condições comerciais ou dispensa obrigações. Não há feature de IA nem cota de IA em falhas no MVP.

## 20. Riscos e mitigação

| Risco | Mitigação aprovada ou necessária |
|---|---|
| MVP maior que o original | Decompor entregas e reestimar; não assumir prazo anterior |
| Retrabalho com sistema existente | Piloto que use lançamento direto; integração posterior |
| Emissão fiscal separada | Procedimento externo viável no piloto; módulo na fase 2 |
| Confirmação atrasada bloquear saída | Consulta/reconciliação e atendimento de pendências; nunca aprovação manual integrada |
| Internet indisponível | Sinalização, rascunhos, contingência manual e reconciliação |
| Desconto/cancelamento indevido | Permissões, justificativa, autoria e auditoria |
| Efeito de rede não comprovado | Valor local primeiro; medir uso entre lojas sem assumir causalidade |
| Dados comerciais insuficientes | Cobertura de classificação explícita e proteção de recortes |
| Recebimento externo divergente | Separação analítica, referência e correção exclusiva do gerente |
| Conta PSP revogada/saldo insuficiente | Pendência explícita; acompanhamento sem declarar devolução concluída |
| Custo operacional superior à receita | Medir suporte e uso integrado/externo; validar mensalidades |
| Falha em recuperação | Testes de restauração e conciliação pós-desastre |

## 21. Sequenciamento recomendado para o desenvolvimento

O backlog executável está em [epicos-e-historias.md](epicos-e-historias.md), com 11 épicos e 54 histórias: base; prova PSP; identidade/estabelecimento; catálogo/comanda; produção; atendimento/correções; fechamento/pagamento/saída; devoluções/débitos; planos/mensalidade; indicadores; operação/piloto.

Uma história por vez, respeitando dependências e gates. Prova do PSP deve começar cedo; falta de credenciais não bloqueia desenvolvimento independente. Piloto só inicia com o fluxo completo e gates produtivos resolvidos, não apenas telas prontas.

## 22. Resumo de substituições do PRD anterior

- Abertura pelo cliente/QR de mesa → abertura exclusiva por funcionário e QR na tela.
- Comanda de pessoa ou mesa → individual; mesa é localização opcional.
- Aplicativo nativo presumido → web/PWA com áreas por perfil.
- Produção fora do escopo → módulo cozinha/bar incluído; pedido pelo cliente continua futuro.
- Login genérico → Google para consumidor opcional, e-mail/senha para equipe e MFA administrativo.
- Estado linear único → ciclos independentes de atendimento, finanças, produção e saída.
- Pagamento somente digital → digital e registro externo, um meio integral por quitação.
- Liberação única → automática ou validada por funcionário, configurável.
- Comissão genérica → somente integrada, total efetivamente pago, com reversão proporcional confirmada.
- Dashboard local básico → operacional/financeiro, multiunidade e comercial agregado protegido.
- Piloto direto em 15 lojas → 2–3 acompanhadas antes de expansão.
- Fiscal sem compromisso definido → fora do MVP, explicitamente na segunda fase.
- Hipótese de preço na abertura rejeitada → preço de produto preservado no envio do pedido.

## 23. Glossário

- **Comanda:** consumo individual de uma pessoa durante um atendimento; não é a tentativa de pagamento.
- **Visita:** permanência no estabelecimento que pode abranger mais de uma comanda, relevante à não repetição do couvert.
- **Pedido:** conjunto enviado pelo funcionário; contém itens com acompanhamento independente.
- **Lançamento/alocação:** quantidade inteira atribuída a uma comanda; pode ser dividida por transferência de unidades, preservando a linha enviada e seu preço.
- **Linha de produção:** sequência única de preparo/entrega de toda quantidade remanescente da linha enviada, mesmo com alocações em comandas diferentes.
- **Avulso:** consumidor sem cadastro, com acesso temporário autorizado por QR.
- **PSP:** provedor que processa pagamentos e informa situação financeira conforme contrato.
- **Split comercial:** divisão da transação entre estabelecimento e fastPay; distinto de split tributário.
- **Quitação:** reconhecimento de pagamento integral ou encerramento sem valor, com distinção entre os casos.
- **Liquidação:** evento financeiro do provedor, diferente de aprovação e liberação de saída.
- **Conciliação:** confronto entre registros fastPay e evidências financeiras externas.
- **Chargeback:** contestação no sistema de cartão, distinta de devolução voluntária.
- **Dia operacional:** intervalo definido por horário de corte e fuso da unidade.
- **RPO/RTO:** metas de perda máxima de dados e tempo de recuperação em desastre.
- **p95:** valor dentro do qual se encontram 95% das observações do indicador.

---

## 24. Detalhamentos aprovados após a versão 2.0

**FR-67 — Identidade visual.** Admin do estabelecimento pode incluir, substituir ou remover um logo opcional, mostrado nas áreas da unidade e comprovante. Sem logo usar nome/iniciais. Um arquivo JPEG/PNG/WebP de até 5 MB, validado no servidor. Foto de produto segue mesmo limite, uma por produto. Comprovação de devolução externa: até 3 JPEG/PNG/PDF de até 10 MB cada, acesso restrito e original preservado.

**FR-68 — Valores e configurações preservados.** Desconto é arredondado HALF_UP em 2 casas; serviço usa base configurada e desconto já arredondado, também HALF_UP2; total C−D+K+S. Desconto fixo efetivo é limitado ao consumo remanescente; se a redução eleva percentual acima da autoridade de quem concedeu, marcar revisão e bloquear cobrança até autorizado revisar, sem impedir correção legítima. Serviço captura configuração na abertura; couvert auto na abertura/manual na inclusão; atendimento e saída capturam modo na abertura. Permissões alteradas têm efeito imediato. Demais detalhes monetários na especificação.

**FR-69 — Limites e lotes.** Inicialmente até 50 linhas distintas por envio, 99 unidades inteiras por linha, 50 linhas por lote de produção, 100 registros por página e 90 dias por exportação. São parâmetros técnicos globais, não cotas comerciais configuráveis pela loja nem limite total da comanda. Lote de produção processa cada linha pelo estado/versão esperado: sucesso parcial explícito, conflitos/canceladas não impedem demais e repetição não avança uma segunda etapa.

**FR-70 — Desfazer entrega.** Gerente ou funcionário com permissão específica pode voltar linha entregue para pronta com motivo/auditoria, somente se todas as comandas com alocações ativas estiverem abertas e sem fechamento/cobrança. Reabrir antes quando elegível; não usar esta ação para ignorar cobrança. Pagas/encerradas admitem ocorrência operacional, sem reabrir/devolver automaticamente. Preservar evento anterior e invalidar sua amostra analítica; nova entrega válida redefine o tempo.

**FR-71 — Atribuição de devolução.** Devolução autorizada por gerente pode identificar item/serviço/couvert ou valor livre justificado. Controlar saldo líquido de desconto e reservas por componente e global; nenhuma atribuição ultrapassa ambos. Valor livre reduz saldo global e aparece como não alocado, sem distribuição fictícia em produtos. Resultado incerto mantém reserva. Devolução não implica quantidade física devolvida/cancelamento/produção desfeita. Comissão só reverte quando confirmada, proporcional ao original; acumulado integral exato. Motivo do couvert afeta visita conforme FR-12.

**FR-72 — Cadastro e ativação.** Separar estado operacional configuração/ativo/suspenso/cancelado de assinatura, PSP e eventos futuros. Configuração não gera comandas reais nem mensalidade. Admin fastPay ativa após verificar cadastro/responsável, elegibilidade e conexão PSP, configurações e condições comerciais; cobrança antecipada marca ciclo conforme FR-52/53. Não permitir operação produtiva sem esses gates. Testes usam homologação/dados isolados, sem contaminar indicadores reais.

**FR-73 — Ciclo suspenso e retorno.** Reativação exclusiva do admin fastPay após regularização/acordo documentado. Havendo ciclo pago ainda vigente, aproveitar até término original, sem estender; caso contrário, novo ciclo antecipado. Suspensão não apaga dívida e pagamentos reais continuam gerando comissão devida. Dono pode desistir do cancelamento programado antes do fim do ciclo, auditado/notificado, mantendo âncora e sem cobrança duplicada. Isso não remove suspensão independente. Após cancelamento efetivo, aplicar reativação, não simples desfazer. Sem pró-rata automático.

**FR-74 — Atualização de painéis.** Atualizar projeções em até 1 minuto, exibir instante dos dados e último resultado com aviso quando desatualizado. Última conciliação é separada. Fechamento, pagamento e saída consultam situação atual, nunca dependem de projeção atrasada. Consolidação do dono respeita permissões atuais e explicita intervalos locais; rede usa fuso de referência configurável, inicialmente America/Sao_Paulo com dias 00 h–00 h.

**FR-75 — Dicionário analítico.** Distribuir desconto de consumo proporcionalmente às linhas por maior resto em centavos, desempate ID estável, soma exata; snapshots pagos não são reescritos por devolução. Quantidade, venda, recebimento e ajustes são medidas distintas. Medir uma amostra por linha de produção, sem multiplicar pela quantidade. Preparo só etapa concluída; produto sem preparo fica fora da média/p95 de preparo. Pronto cancelado conserva preparo e não inventa entrega; cancelados e pendentes exibem métricas próprias. Transferência não duplica fatos; entrega desfeita invalida amostra anterior. Demanda usa data de envio e calendário aplicável; recebimento e devolução usam confirmação efetiva. Não calcular p95 global como média dos p95 locais.

**FR-76 — Exportação assíncrona.** Uma exportação ativa por usuário/unidade, processamento e falha visíveis; filtros, período e instante de referência registrados. Arquivo disponível por 24 horas com permissão revalidada no download. Expiração elimina artefato, não registros de origem. Mesmo pedido/retry não duplica execução. Relatório de parceiro respeita P-09 mesmo com poucas lojas; limite de recorte não pode ser reduzido silenciosamente.

**FR-77 — Responsável e gate do piloto.** Diego responde pelo acompanhamento e aceite, com contato manual de suporte (WhatsApp) e e-mail de alertas a configurar; horários acordados, sem promessa implícita de 24 h. Antes de liberação, comprovar jornada completa abertura→pedido→produção→entrega→conferência→pagamento→saída; permissões/isolation; duplicidade/incerteza/refunds; restore com tempo medido; monitoramento/alertas e contingência/equipe; gates cadastrais, comerciais e de privacidade resolvidos. Cobrança errada, pedido perdido e acesso indevido bloqueiam. Ajustes menores de apresentação podem ser aceitos por Diego com registro. Backups contínuos começam antes do primeiro dado real, incluindo piloto; homologação tem backups pontuais e ensaio de restore.

### 24.1 Identidade e privacidade

Sessões da equipe: garçom/produção 1 h inativa e 12 h absoluta; gerente/admin loja 30 min e 12 h; admin fastPay 15 min e 8 h. Consumidor Google 7 dias inativo e 30 dias absolutos. Tráfego automático não é atividade. Avulso segue FR-11 com grant persistente/revogável. Convites48 h; reset 30 min; MFA privilegiado com TOTP e 10 códigos de recuperação de uso único. Recuperação por senha+código recadastra MFA em fluxo restrito, invalida anteriores/sessões e notifica. Recuperação excepcional sem códigos segue P-07, não e-mail sozinho.

Consumidor pode solicitar exclusão autenticada mesmo com pendências; registrar solicitação e acompanhar, retendo apenas dados necessários para resolver/obrigações conforme política aprovada. Desativar vínculo da equipe não exclui identidade de outras unidades; último admin exige substituição. Cancelamento do estabelecimento é fluxo separado. Prazos/anonimização/exportação pessoal/retorno de backups dependem P-08, sem exclusão cega de histórico financeiro.

### 24.2 Processo e referências do desenvolvimento

A especificação técnica define monólito modular Java/Spring e Angular, Docker na Hostinger, homologação Windows/WSL2, SES/Gmail, S3, PITR e futura migração AWS. Versões exatas/gates estão no documento técnico. Execução por Codex ou Claude Code, uma história por vez, Git Flow com develop. `AGENTS.md` é regra comum; `CLAUDE.md` a importa. Branch/commit/push/PR autorizados durante implementação solicitada; merge/deploy só com solicitação explícita. Evidências e estado em progresso.md; história concluída requer merge em develop e checks, não apenas código escrito.

**Fim do PRD v3.0.** Ler em conjunto com [especificacao-tecnica.md](especificacao-tecnica.md), [decisoes-pendentes.md](decisoes-pendentes.md) e [epicos-e-historias.md](epicos-e-historias.md). Requisitos FR-01–66 e NFR/CA foram preservados e revisados pontualmente; FR-67–77 acrescentam os detalhamentos aprovados.
