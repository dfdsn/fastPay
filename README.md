# fastPay

Monólito modular (Spring Boot + Spring Modulith) com frontend Angular. Documentação consolidada em 06/10/2026; bootstrap técnico (H01) em 07/10/2026. Regras comerciais ainda não implementadas.

## Como executar

Pré-requisitos (versões exatas em [evidencias/H01-build.md](docs/evidencias/H01-build.md)):

- JDK Temurin 25 com `JAVA_HOME` apontando para ele. Maven não precisa estar instalado: use o wrapper (3.9.16, checksum fixado).
- Node 24.18.0 (`frontend/.nvmrc`) com o npm 11.16.0 que o acompanha. O projeto recusa outro npm (`engine-strict`); um npm global mais antigo em `%APPDATA%\npm` precisa ser removido ou contornado.

| Comando (a partir da raiz) | Uso |
|---|---|
| `./scripts/verify.sh` | Verificação completa: backend `clean verify` + frontend `npm ci`, lint, testes, build |
| `cd backend` e `./mvnw verify` (`mvnw.cmd` no Windows) | Compilar, testes JUnit, fronteiras Modulith, JaCoCo |
| `java -jar backend/target/fastpay-backend-0.1.0-SNAPSHOT.jar` | Subir o backend em `http://localhost:8080` |
| `cd frontend` e `npm ci` | Instalar exatamente o lockfile |
| `cd frontend` e `npm run lint` | ESLint (TypeScript, templates e acessibilidade) |
| `cd frontend` e `npm run test:ci` | Vitest não interativo com cobertura em `frontend/coverage/` |
| `cd frontend` e `npm run build` | Build de produção em `frontend/dist/` |
| `cd frontend` e `npm start` | Servidor de desenvolvimento em `http://localhost:4200`, com `/api` encaminhado ao backend |
| `./scripts/verify.sh backend` ou `frontend` | Só uma das metades (é o que cada job da CI roda) |

### Docker (H02)

Requer Docker Desktop (WSL2) com Compose v2. Copie `.env.example` para `.env` e defina `POSTGRES_PASSWORD`.

| Comando (a partir da raiz) | Uso |
|---|---|
| `docker compose --env-file .env -f infra/compose.dev.yml up -d --build` | Banco, backend e frontend locais; abrir `http://127.0.0.1:8088` |
| `docker compose --env-file .env -f infra/compose.dev.yml down` | Parar sem apagar o volume do banco (`down -v` apaga os dados locais) |
| `./scripts/compose-smoke.sh` | Smoke isolado (projeto `fastpay-smoke`): saúde, `/api`, banco sem porta publicada, dado preservado após recriar |
| `docker compose --env-file .env.hml -f infra/compose.hml.yml up -d` | Homologação com as imagens publicadas pela CI no GHCR (`FASTPAY_IMAGE_OWNER`, `FASTPAY_VERSION`) |

O banco não tem porta publicada no host. A CI (`.github/workflows/ci.yml`) testa PRs para `develop`/`main` e publica imagens privadas no GHCR apenas em push para essas branches; não faz deploy.

Endpoints atuais: `GET /actuator/health` (somente status) e `GET /api/v1/platform/info` (nome, versão e ambiente). Variáveis em [.env.example](.env.example).

## Documentação

| Arquivo | Finalidade |
|---|---|
| [PRD-fastPay-atualizado.md](docs/PRD-fastPay-atualizado.md) | Produto, regras e critérios; v3.0 substitui v2 |
| [especificacao-tecnica.md](docs/especificacao-tecnica.md) | Stack, arquitetura, dados, autenticação, jobs, integrações e VPS |
| [decisoes-pendentes.md](docs/decisoes-pendentes.md) | O que foi decidido e o que ainda bloqueia cada entrega |
| [epicos-e-historias.md](docs/epicos-e-historias.md) | 11 épicos e54 histórias com dependências/aceite |
| [AGENTS.md](AGENTS.md) | Regras comuns para Codex e Claude Code |
| [CLAUDE.md](CLAUDE.md) | Entrada do Claude Code com importação de AGENTS |
| [progresso.md](docs/progresso.md) | Estado inicial e registros de implementação/testes/PR |

Ordem de leitura: a da tabela. backend, frontend e scripts foram criados em H01; infra e CI em H02. As instruções incluem a leitura das referências; não dependem de memória da conversa. Usar Codex OU Claude Code, uma história por vez.

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
