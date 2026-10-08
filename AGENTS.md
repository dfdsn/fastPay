# Instruções compartilhadas — fastPay

Aplicam-se a Codex e Claude Code no repositório. Produto em português do Brasil; código segue nomes claros e convenções do projeto. Uma história e um agente por vez. Não executar lotes ou agentes paralelos. Não iniciar implementação de fase 2 no MVP.

## Leitura inicial

Antes de alterar código, leia:
1. `docs/PRD-fastPay-atualizado.md` — comportamento e escopo.
2. `docs/especificacao-tecnica.md` — arquitetura e contratos técnicos.
3. `docs/decisoes-pendentes.md` — gates/decisões.
4. `docs/epicos-e-historias.md` — história, critérios e dependências.
5. `docs/progresso.md` — estado real e próximo passo.
6. README, manifests e instruções locais dos diretórios afetados, quando existirem.

Não é suficiente que os arquivos estejam presentes: abra os relevantes. Pedido explícito do usuário prevalece; registre a mudança nos documentos afetados. PRD define regra de produto; especificação define implementação; histórias não alteram regra implicitamente; progresso só registra evidência. Havendo conflito não resolvido, mostre o trecho e pergunte sobre o ponto afetado, sem inventar aprovação. A versão 3 do PRD já incorpora decisões posteriores e substitui v2.

## Seleção e execução

- Se foi indicada uma história, verifique dependências e gates. Sem ID, retome a história em andamento; se nenhuma, escolha a menor ID elegível. Não pular silenciosamente critérios.
- História aguardando validação/revisão é trabalho ativo: trate feedback/pendências. Não iniciar outra até encerrar ou o usuário explicitamente autorizar a troca, deixando registro.
- Gate externo bloqueado: conclua o trabalho independente dentro da história, registre bloqueio/evidência e próximo passo. Não represente mocks como prova PSP. Outra história pode ser escolhida em pedido posterior, com troca explícita no progresso, sem paralelismo.
- Inspecione árvore, `git status`, branches e diff. Não sobrescreva trabalho alheio. Se não existe repositório/remoto, não invente URL; trabalhe localmente e registre o acesso faltante.
- Declare objetivo e plano curto. Atualize progresso para em andamento e mantenha comunicação sobre achados relevantes.
- Implemente a menor solução completa para os critérios; decisões técnicas rotineiras dentro da stack são autorizadas. Não pedir confirmação a cada arquivo/teste.
- Preserve rastreabilidade: regra→história→código→teste. Ajuste documentação junto da mudança. Não redesenhe módulos não relacionados.

## Git e autorização

Git Flow: feature a partir de `develop`, PR para `develop`; release estabiliza, PR para `main` e sincroniza `develop`; hotfix de `main` volta às duas. `main` estável com tags de release. Nunca considerar merge um deploy.

Autorizado no desenvolvimento solicitado: criar branch, editar, testar, commit, push e abrir PR. Convenção de branch `feature/Hxx-descricao`; commits claros, com ID da história. Inclua no PR problema, mudança, resultado, testes reais e riscos/gates. Não fazer merge ou deploy sem solicitação explícita. Não forçar push, apagar dados/remotos/branches úteis ou reescrever histórico sem autorização. Não enviar e-mails/mensagens a terceiros a título de teste sem autorização; usar caixas/contas de teste adequadas.

Antes de commit confira secrets/dados pessoais e diff. Faça commit apenas dos arquivos da história. Não adicionar segredos, dumps produtivos ou provas de pagamento ao Git. Publicar no GHCR só conforme pipeline aprovado e permissões de ambiente.

## Padrões obrigatórios

- Java25/Boot e Angular conforme especificação; versões reprodutíveis. Não adicionar serviço/SDK/major novo ou custo sem justificar e obter decisão quando mudar escopo.
- Monólito modular: APIs públicas entre módulos; domínio sem JPA/HTTP; entidades/repositórios não cruzam fronteira. Sem abstrações vazias.
- Toda ação autoriza ator, unidade e permissão no backend. RLS com runtime não privilegiado, inclusive jobs/exports/SSE.
- Valores com BigDecimal/decimal string, HALF_UP2 e regras originais preservadas. Nunca confiar no total do frontend.
- Transações curtas, versões/locks nas invariantes e idempotência. Sem chamada PSP dentro de lock DB. Timeout financeiro nunca libera nova tentativa por suposição.
- Migração Flyway nova, imutável após aplicada; Hibernate validate. Testar com PostgreSQL real. Destruição de dados requer plano específico.
- Eventos duráveis no mesmo commit, consumidores idempotentes e tenant validado. Não financeiros 5 tentativas; financeiro incerto mantém conciliação.
- Frontend standalone/Signals/RxJS/Reactive Forms/Material, limpar contexto e assinaturas. Não aprovação offline, nem cache como fonte financeira.
- Não expor senha/token/PAN/CVV nos logs, eventos, commits ou relatórios. Arquivos privados e download autorizado.
- Manter histórico auditável; não apagar eventos para “consertar” produção, recebimento, devolução ou saída.
- Todo texto de erro deve ser útil, seguro e em português para a UI. Acessibilidade por teclado/foco e rótulos desde a primeira tela.

## Comandos e ambiente

O pacote inicial contém documentação, não código executável. H01/H02 criam scripts/manifests e devem atualizar esta seção com comandos efetivamente validados. Não afirmar que estes comandos já existem ou rodaram.

| Comando planejado (da raiz, salvo indicado) | Uso / quando existir |
|---|---|
| `cd backend` e `./mvnw verify` | Compilar, testes de unidade/integração e gates Maven |
| `cd frontend` e `npm ci` | Instalar lockfile |
| `cd frontend` e `npm run lint` | Lint configurado em H01 |
| `cd frontend` e `npm run test:ci` | Vitest não interativo em H01 |
| `cd frontend` e `npm run build` | Build produção |
| `cd frontend` e `npm run e2e` | Playwright, após servidor/ambiente documentado |
| `docker compose -f infra/compose.dev.yml up -d` | Serviços locais após H02; não produção |

Executar no WSL2 para manter paridade com Linux; Docker Desktop deve estar acessível. Conferir scripts reais antes de usar. CI e local precisam mesmas ferramentas/lockfiles. Se nome real divergir, atualizar docs sem deixar comando fictício.

## Testes e evidências

Teste proporcional ao risco e critérios. Obrigatórios conforme mudança: domínio, PostgreSQL/RLS/concorrência, módulos, frontend e E2E. Backend80%linhas/70%branches em domain/application; frontend relatório e cenários; PIT em regras críticas, threshold calibrado. Não criar testes que só espelham implementação. Não ampliar escopo de testes sem risco concreto ou gate.

Para cada execução registre comando, ambiente, commit, resultado e caminho/CI da evidência. Sem execução: “não executado” com motivo. Teste com mock ≠ homologação de provedor; build ≠ jornada validada. Logs anexos sem dados reais/segredos. Falha de infraestrutura não é passe.

Não desabilitar teste/constraint/RLS para obter verde. Se flaky, diagnosticar e registrar; quarentena de gate crítico requer decisão explícita. Não usar rollback/restore produtivo como experimento.

## Progresso e definição de concluída

Estados: não iniciada; em andamento; bloqueada; aguardando validação; aguardando revisão; concluída.

Concluída = critérios atendidos, documentação/evidências atualizadas, checks obrigatórios aprovados e merge em `develop` verificado. PR aberto é aguardando revisão. Código com validação externa pendente é aguardando validação/bloqueada, não concluído. Merge em develop não significa publicado/ativado. Após merge autorizado, reconciliar progresso com hash/PR reais em atualização subsequente; não inventar hash futuro para fechar o próprio PR.

Atualize `docs/progresso.md`: história, agente, branch, commit/PR, critérios atendidos/faltantes, testes, bloqueios, próximo passo. Preserve evidências anteriores. Novas decisões em `decisoes-pendentes.md`; mudanças aprovadas de regra também no PRD/spec/histórias. Não usar conversa como único registro.

Resposta de entrega: o que mudou/por quê, testes e limitações reais, estado da história, PR/arquivos e próximo passo. Não iniciar automaticamente outra história. Diego decide validação, merge, release e deploy.
