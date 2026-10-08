# fastPay — pacote de preparação para desenvolvimento

Consolidado em 06/10/2026 para Diego. Contém documentos; não contém a aplicação implementada.

## Arquivos e ordem de leitura

| Arquivo | Finalidade |
|---|---|
| [PRD-fastPay-atualizado.md](docs/PRD-fastPay-atualizado.md) | Produto, regras e critérios; v3.0 substitui v2 |
| [especificacao-tecnica.md](docs/especificacao-tecnica.md) | Stack, arquitetura, dados, autenticação, jobs, integrações e VPS |
| [decisoes-pendentes.md](docs/decisoes-pendentes.md) | O que foi decidido e o que ainda bloqueia cada entrega |
| [epicos-e-historias.md](docs/epicos-e-historias.md) | 11 épicos e54 histórias com dependências/aceite |
| [AGENTS.md](AGENTS.md) | Regras comuns para Codex e Claude Code |
| [CLAUDE.md](CLAUDE.md) | Entrada do Claude Code com importação de AGENTS |
| [progresso.md](docs/progresso.md) | Estado inicial e registros de implementação/testes/PR |

Coloque AGENTS.md, CLAUDE.md e este README na raiz do repositório, com a pasta docs preservada. O bootstrap criará backend/frontend/infra/scripts. As instruções incluem a leitura das referências; não dependem de memória da conversa. Usar Codex OU Claude Code, uma história por vez.

Pedido inicial sugerido:

> Implemente a H01 do fastPay seguindo as instruções do repositório. Atualize o progresso com evidências reais. Abra PR para develop quando os critérios estiverem atendidos; não faça merge nem deploy.

Depois pode pedir “Implemente a próxima história elegível”. Se houver história em revisão/validação, retome essa antes de iniciar outra. Branch, commits, push e PR estão autorizados nesse fluxo; merge e deploy continuam decisões explícitas suas. A falta de remoto/credenciais será registrada, sem simular ações.

## O que mudou nesta consolidação

- Quantidade parcial pode ser transferida/cancelada em unidades inteiras; produção continua uma sequência por linha. Isso substitui restrição da v2.
- Incluídos logo, limites, regras de lote, reversão de entrega, detalhamento de métricas/devoluções e gate do piloto.
- Stack/infra aprovadas documentadas; patches/compatibilidade final ficam como trabalho verificável do bootstrap.
- Valorzero definido; cota de IA não se aplica; exclusão de conta tem fluxo acordado e retenção ainda depende validação.
- Emissão fiscal explicitamente fase 2; no MVP há procedimento externo.

Pendências externas não impedem começar H01. Elas impedem concluir os fluxos afetados e liberar dinheiro real sem as validações correspondentes. Diego permanece responsável pelo aceite e acompanhamento do piloto. Nenhuma conta, domínio, serviço pago ou ambiente produtivo foi alterado por este pacote.
