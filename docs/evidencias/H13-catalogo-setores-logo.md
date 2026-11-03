# H13 — Evidência de catálogo, setores, disponibilidade e logo

Registro sanitizado. Claude Code, 02/11/2026, sessão em nuvem (Linux) com Docker. Sem segredos nem dados pessoais: identidades de teste com e-mail `@teste.invalid`, senhas fictícias, chaves e senhas de banco geradas a cada execução; imagens sintéticas geradas pelos próprios testes (e duas amostras WebP de 200 bytes em `backend/src/test/resources/imagens/`, geradas com Pillow 12.3).

## 1. O que foi entregue

| Peça | Arquivo | Decisão |
|---|---|---|
| Schema e RLS | `V10__catalogo_setores_e_imagens.sql` | `file_object` (metadados de imagem regravada, remoção lógica), `sector`, `product` (preço `numeric(7,2) > 0`, setor opcional = sem preparo, foto opcional, `archived_at`, `version`), FKs compostas por unidade para setor, foto e logo, nomes únicos entre ativos sem diferenciar maiúsculas, `establishment.logo_file_id` com policy de UPDATE só da unidade do contexto. Todas com RLS `ENABLE + FORCE` |
| Permissões | `identity.Permission` (`GERIR_CATALOGO`, `CONFIGURAR_ESTABELECIMENTO`), `identity.domain.AccessPolicy` | Catálogo, setores e logo são do administrador do estabelecimento (PRD §3), não delegáveis; disponibilidade segue `ALTERAR_DISPONIBILIDADE` (gerente, admin, garçom com delegação) da H11 |
| Imagens | módulo `files`: `Images`, `domain.ImageProcessor`, `domain.ImageFormat`, `domain.ExifOrientation`, `application.StoragePort`, `infrastructure.LocalFileStorage`, `JdbcFileStore`, `web.FilesController`, `web.UploadProblems` | Verificação antes da transação, regravação obrigatória, storage local por interface, download autorizado por vínculo. Ver seção 2 |
| Catálogo | módulo `catalog`: `Catalog`, `domain.CatalogRules`, `infrastructure.JdbcCatalogStore`, `web.CatalogController` (`/api/v1/catalog`) | Consulta para toda a equipe (preço só para quem opera comanda); CRUD com versão esperada e linha travada; arquivar em vez de apagar; auditoria por mudança |
| Logo | `establishments.application.Branding`, `establishments.web.BrandingController` (`/api/v1/establishment`) | Incluir, substituir e remover; anterior deixa de ser servido; fallback de iniciais na tela |
| Frontend | `catalog/catalog-page.ts`, `catalog/catalog.service.ts`, `branding/branding-page.ts`, `branding/unit-logo.ts`, `branding/branding.service.ts`; rotas `/catalogo` e `/logo`; logo/iniciais e links no início da unidade | Estados de carregando/erro/vazio, mensagens do servidor, rótulos acessíveis em cada botão, aviso local de 5 MB antes de enviar, recarga ao detectar versão desatualizada |
| Infra | `infra/backend.Dockerfile`, `infra/compose.dev.yml`, `infra/compose.hml.yml`, `frontend/nginx/default.conf.template`, `.env.example` | Pasta `/var/lib/fastpay/arquivos` do usuário `fastpay` com volume `files-data`; `client_max_body_size 6m` no `/api/` |

**Dependência nova:** `com.twelvemonkeys.imageio:imageio-webp:3.12.0` (traz `imageio-core`, `imageio-metadata`, `common-lang`, `common-io`, `common-image` da mesma família). Motivo: FR-67 exige WebP e o ImageIO do JDK só lê JPEG/PNG. Só decodifica; a gravação é feita pelo JDK. Licença BSD-3, puro Java, sem serviço externo nem custo. Enforcer (`dependencyConvergence`) passou. Registrada em T-01; aceita por Diego em 03/11/2026, junto com as propostas T-03 e T-04 da H13.

## 2. Verificação de imagem (propostas T-03/T-04)

| Etapa | Regra | Recusa |
|---|---|---|
| Tamanho | até 5 MB (5.242.880 bytes); o servidor aceita corpo de até 6 MB para responder com a mensagem própria | 413 `ARQUIVO_GRANDE` |
| Formato | assinatura do conteúdo: JPEG, PNG ou WebP; nome do arquivo e Content-Type ignorados | 422 `FORMATO_NAO_ACEITO` |
| Dimensões | lidas do cabeçalho antes de decodificar; mínimo 64 px por lado, máximo 8000 px por lado e 40 MP | 422 `IMAGEM_PEQUENA` / `IMAGEM_GRANDE` |
| Decodificação | completa; imagem muito maior que a exibida é lida com subamostragem (menos memória) | 422 `IMAGEM_INVALIDA` |
| Regravação | giro EXIF aplicado nos pixels; maior lado até 1600 px; JPEG qualidade 0,85, ou PNG se há transparência; metadados e dados anexados não passam | — |
| Guarda | só a imagem regravada, chave `<unidade>/<id aleatório>`, SHA-256 e dimensões no banco | — |
| Download | `GET /api/v1/files/{id}` com vínculo ativo na unidade da sessão; tipo guardado, `nosniff`, CSP `default-src 'none'; sandbox`, cache privado de 1 dia | 404 `ARQUIVO_NAO_ENCONTRADO` (outra unidade, removido ou inexistente) |

## 3. Testes

| Teste | Critério da H13 |
|---|---|
| `ImageProcessorTest` (11) | JPEG, PNG opaco/transparente e WebP com e sem perdas aceitos; GIF, SVG, PDF e HTML recusados pelo conteúdo; vazio e acima de 5 MB; truncado/corrompido (inclusive WebP falso); PNG declarando 50.000 × 50.000 recusado sem decodificar; lado acima de 8000, acima de 40 MP e abaixo de 64; EXIF com "GPS" e `<script>` anexado não aparecem no guardado; orientação EXIF 6 girada; redução para 1600 px |
| `ExifOrientationTest` (10) | Leitura defensiva (sem EXIF, truncado, valor inválido) e as 8 orientações |
| `ImageFormatTest` | Assinaturas |
| `CatalogRulesTest` (24) | Preço em formato brasileiro e com ponto, sem arredondar; zero, negativo, 3 casas, acima do máximo recusados; limites de nome, categoria, descrição e setor |
| `AccessPolicyTest` (28) | Matriz por papel incluindo `GERIR_CATALOGO` e `CONFIGURAR_ESTABELECIMENTO` só para admin |
| `CatalogIntegrationTest.donoCadastraSetoresEProdutosEEquipeConsultaConformePapel` | CRUD de setor/produto, produto sem preparo, listagem por categoria, preço visível ao garçom e oculto à produção, auditoria |
| `…soOAdministradorAlteraOCatalogo` | **Permissão:** gerente, garçom e produção recebem 403 ao criar setor/produto; sem unidade escolhida 409 |
| `…disponibilidadePorGerenteEGarcomComPermissaoDelegada` | **Produto esgotado:** gerente marca; garçom só com delegação (vale na sessão ativa); repetição idempotente sem auditoria duplicada; esgotado continua listado |
| `…versaoNomeRepetidoSetorEmUsoEArquivamento` | Versão desatualizada, nome repetido (maiúsculas), campo inválido com `field`, setor em uso, arquivamento preserva a linha e libera o nome, setor arquivado recusado, auditoria do preço antes/depois |
| `…outraUnidadeNaoAlcancaProdutoSetorNemArquivo` | **fileID de outra unidade:** 404 no download; produto, disponibilidade e foto de outra unidade 404; setor de outra unidade 422 |
| `…fotoDoProdutoIncluidaSubstituidaERemovida` | Foto regravada e reduzida (sem os metadados do original), headers do download, substituição e remoção deixam de servir o anterior, auditoria |
| `…imagemRecusadaPorConteudoTamanhoEDimensoes` | **Imagem inválida/tamanho/dimensões** pela API, com mensagem em português; nenhum arquivo gravado |
| `…logoIncluidoSubstituidoERemovidoComFallback` | **Logo removido:** inclusão (PNG transparente preservado), toda a equipe vê, só admin altera, substituição e remoção param de servir o anterior, auditoria, outra unidade 404 |
| `…runtimeSemContextoNaoEnxergaCatalogo` | RLS: o papel do runtime sem contexto não vê produto, setor nem arquivo |
| `MigrationsIntegrationTest` | V1–V10 em banco vazio e de V1 para V10 |
| Frontend `catalog-page.spec.ts` (11), `branding-page.spec.ts` (4), `branding.service.spec.ts`, `unit-home-page.spec.ts` | Listagem por categoria, preço, setor, foto, esgotado; produção sem preços e sem botões; gerente só disponibilidade; cadastro com preço em texto; preço inválido não enviado; edição com versão e recarga em `VERSAO_DESATUALIZADA`; setores; envio de foto em `FormData` e aviso local acima de 5 MB; paginação; erro com nova tentativa; logo incluir/remover/iniciais; links do início por permissão |

## 4. Execuções

| Comando | Ambiente | Resultado |
|---|---|---|
| `./mvnw -B -ntp verify` (backend) | contêiner `eclipse-temurin:25.0.4.1_1-jdk-noble`, Docker para o Testcontainers (PostgreSQL 18.6 da imagem fixada), sessão em nuvem, 02/11/2026 | **301 testes, 0 falhas**; enforcer, Modulith e JaCoCo ("All coverage checks have been met") ok |
| `npm ci`, `npm run lint`, `npm run test:ci`, `npm run build` (frontend) | contêiner `node:24.18.0-trixie-slim`, sessão em nuvem, 02/11/2026 | lint ok; **118 testes**, 0 falhas; build ok (aviso NG8102 já existente em `team-page.ts`) |

Não executados: `scripts/compose-smoke.sh` e build das imagens (rodam na CI do PR; a mudança de Dockerfile/Compose é a pasta e o volume de arquivos); E2E Playwright (não configurado); teste manual na homologação da máquina de Diego.

## 5. Limitações e próximos passos

- Storage local apenas (dev/HML). S3 privado, versionamento e recuperação de objetos na H49/H51; o backup do PostgreSQL não recupera arquivos (especificação §13).
- Bytes de imagens removidas ficam no disco até existir a política de retenção (P-08).
- Sem limite de processamentos de imagem simultâneos: com a subamostragem, uma imagem de 40 MP usa memória proporcional ao dobro do tamanho final. Medir na H53.
- JPEG CMYK (raro, de gráficas) não é lido pelo JDK e é recusado como `IMAGEM_INVALIDA`.
- Preço capturado no envio, conferência de disponibilidade no envio e fila por setor chegam em H16/H18; catálogo de referência da rede em H44.
