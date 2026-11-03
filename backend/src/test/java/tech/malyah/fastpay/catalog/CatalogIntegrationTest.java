package tech.malyah.fastpay.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.files.TestImages;
import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.identity.infrastructure.MfaSecretCipher;
import tech.malyah.fastpay.identity.web.Browser;
import tech.malyah.fastpay.identity.web.StaffLogin;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.ControlledClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Catálogo, setores, disponibilidade, foto e logo pela API (H13, FR-13, FR-14, FR-18, FR-67), com PostgreSQL real,
 * RLS, Spring Session e CSRF. Cobre a validação exigida no backlog: permissão, imagem inválida/tamanho/dimensões,
 * arquivo de outra unidade, produto esgotado e logo removido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClock.class)
class CatalogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    MfaSecretCipher cifra;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    StaffLogin login;

    UUID loja;
    UUID outraLoja;
    UUID dono;
    UUID gerente;
    UUID garcom;
    UUID producao;
    UUID donoDaOutra;
    UUID vinculoGarcom;

    @BeforeEach
    void montarLojas() {
        relogio.advance(Duration.ofSeconds(30));
        login = new StaffLogin(mvc, fixtures, new MfaFixtures(admin(), cifra), relogio);
        loja = fixtures.establishment("Bar do Catálogo");
        outraLoja = fixtures.establishment("Outro Bar");
        dono = fixtures.identity("Dona Rita");
        gerente = fixtures.identity("Gil Gerente");
        garcom = fixtures.identity("Gabi Garçom");
        producao = fixtures.identity("Paulo Produção");
        donoDaOutra = fixtures.identity("Otto Outro");
        fixtures.membership(dono, loja, MembershipRole.ADMIN_ESTABELECIMENTO);
        fixtures.membership(gerente, loja, MembershipRole.GERENTE);
        vinculoGarcom = fixtures.membership(garcom, loja, MembershipRole.GARCOM);
        fixtures.membership(producao, loja, MembershipRole.PRODUCAO);
        fixtures.membership(donoDaOutra, outraLoja, MembershipRole.ADMIN_ESTABELECIMENTO);
    }

    @Test
    void donoCadastraSetoresEProdutosEEquipeConsultaConformePapel() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String cozinha = id(rita.post("/api/v1/catalog/sectors", "{\"name\":\" Cozinha \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cozinha")));
        String batata = id(rita.post("/api/v1/catalog/products", produto("Batata frita", "Porções", "35,90", cozinha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(35.90))
                .andExpect(jsonPath("$.sectorId").value(cozinha))
                .andExpect(jsonPath("$.available").value(true)));
        // Sem setor: dispensa produção. Preço como número JSON também vale.
        rita.post("/api/v1/catalog/products",
                "{\"name\":\"Água 500 ml\",\"category\":\"Bebidas\",\"price\":6.5,\"sectorId\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.sectorId").value(nullValue()))
                .andExpect(jsonPath("$.price").value(6.5));

        rita.get("/api/v1/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.sectors.length()").value(1))
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].name").value("Água 500 ml"))
                .andExpect(jsonPath("$.products[1].id").value(batata))
                .andExpect(jsonPath("$.categories").value(org.hamcrest.Matchers.contains("Bebidas", "Porções")))
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.pricesVisible").value(true));

        // Garçom vê preços; produção vê o catálogo sem valores (FR-18).
        login.inUnit(garcom, loja).get("/api/v1/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.products[1].price").value(35.90));
        login.inUnit(producao, loja).get("/api/v1/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.pricesVisible").value(false))
                .andExpect(jsonPath("$.products[1].price").value(nullValue()))
                .andExpect(jsonPath("$.products[1].sectorId").value(cozinha));

        assertThat(admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE establishment_id = ? AND actor_id = ?
                """, String.class, loja, dono)).containsExactlyInAnyOrder("setor.criado", "produto.criado",
                "produto.criado");
    }

    @Test
    void soOAdministradorAlteraOCatalogo() throws Exception {
        Browser gil = login.inUnit(gerente, loja);
        Browser gabi = login.inUnit(garcom, loja);
        Browser paulo = login.inUnit(producao, loja);
        for (Browser semPermissao : List.of(gil, gabi, paulo)) {
            semPermissao.post("/api/v1/catalog/sectors", "{\"name\":\"Bar\"}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
            semPermissao.post("/api/v1/catalog/products", produto("Cerveja", "Bebidas", "10", null))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        }
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.product WHERE establishment_id = ?",
                Integer.class, loja)).isZero();

        // Sem unidade escolhida, nada.
        Browser semUnidade = login.signIn(dono);
        semUnidade.get("/api/v1/catalog").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDADE_NAO_SELECIONADA"));
    }

    @Test
    void disponibilidadePorGerenteEGarcomComPermissaoDelegada() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String chopp = id(rita.post("/api/v1/catalog/products", produto("Chopp 300 ml", "Bebidas", "9,90", null)));
        Browser gabi = login.inUnit(garcom, loja);
        gabi.put(disponibilidade(chopp), "{\"available\":false}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        login.inUnit(gerente, loja).put(disponibilidade(chopp), "{\"available\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        // Repetir não muda nada nem gera outra auditoria.
        login.inUnit(gerente, loja).put(disponibilidade(chopp), "{\"available\":false}").andExpect(status().isOk());
        assertThat(admin().queryForObject("""
                SELECT count(*) FROM fastpay.audit_event WHERE establishment_id = ? AND action = 'produto.esgotado'
                """, Integer.class, loja)).isEqualTo(1);

        // Delegada ao garçom, vale já na sessão aberta.
        admin().update("""
                INSERT INTO fastpay.membership_permission (id, establishment_id, membership_id, permission, granted_by, granted_at)
                VALUES (?, ?, ?, 'ALTERAR_DISPONIBILIDADE', ?, now())
                """, UUID.randomUUID(), loja, vinculoGarcom, dono);
        gabi.put(disponibilidade(chopp), "{\"available\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        gabi.put(disponibilidade(chopp), "{}").andExpect(status().isBadRequest());

        // Produto esgotado continua no catálogo, marcado.
        rita.put(disponibilidade(chopp), "{\"available\":false}").andExpect(status().isOk());
        rita.get("/api/v1/catalog").andExpect(jsonPath("$.products[0].available").value(false));
    }

    @Test
    void versaoNomeRepetidoSetorEmUsoEArquivamento() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String bar = id(rita.post("/api/v1/catalog/sectors", "{\"name\":\"Bar\"}"));
        rita.post("/api/v1/catalog/sectors", "{\"name\":\"BAR\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOME_EM_USO"));
        rita.post("/api/v1/catalog/sectors", "{\"name\":\"  \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.field").value("name"));

        String caipirinha = id(rita.post("/api/v1/catalog/products", produto("Caipirinha", "Drinks", "22", bar)));
        rita.post("/api/v1/catalog/products", produto("caipirinha", "Drinks", "25", bar))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOME_EM_USO"));
        rita.post("/api/v1/catalog/products", produto("Caipiroska", "Drinks", "0", bar))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("price"));

        // Alteração com a versão lida; outra versão é recusada sem sobrescrever.
        rita.put("/api/v1/catalog/products/" + caipirinha, produtoVersao("Caipirinha de limão", "Drinks", "24,00", bar, 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.price").value(24.0));
        rita.put("/api/v1/catalog/products/" + caipirinha, produtoVersao("Caipirinha velha", "Drinks", "20", bar, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        assertThat(admin().queryForObject("SELECT detail FROM fastpay.audit_event WHERE subject_id = ?::uuid"
                + " AND action = 'produto.alterado'", String.class, caipirinha)).isEqualTo("nome; preco 22.00>24.00");

        // Setor com produto ativo não é arquivado; depois de arquivar o produto, pode.
        rita.post("/api/v1/catalog/sectors/" + bar + "/archive", "{\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETOR_EM_USO"));
        rita.post("/api/v1/catalog/products/" + caipirinha + "/archive", "{\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSAO_DESATUALIZADA"));
        rita.post("/api/v1/catalog/products/" + caipirinha + "/archive", "{\"version\":1}").andExpect(status().isOk());
        rita.post("/api/v1/catalog/sectors/" + bar + "/archive", "{\"version\":0}").andExpect(status().isOk());
        rita.get("/api/v1/catalog").andExpect(jsonPath("$.products.length()").value(0))
                .andExpect(jsonPath("$.sectors.length()").value(0));
        // Linha preservada para o histórico; o nome volta a ficar livre.
        assertThat(admin().queryForObject("SELECT archived_at IS NOT NULL FROM fastpay.product WHERE id = ?::uuid",
                Boolean.class, caipirinha)).isTrue();
        rita.post("/api/v1/catalog/products", produto("Caipirinha de limão", "Drinks", "24", null))
                .andExpect(status().isOk());
        rita.post("/api/v1/catalog/products", produto("Chá", "Bebidas", "8", bar))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SETOR_INVALIDO"));
        rita.put("/api/v1/catalog/products/" + UUID.randomUUID(), produtoVersao("X", "Y", "1", null, 0))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NAO_ENCONTRADO"));
    }

    @Test
    void outraUnidadeNaoAlcancaProdutoSetorNemArquivo() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String cozinha = id(rita.post("/api/v1/catalog/sectors", "{\"name\":\"Cozinha\"}"));
        String pastel = id(rita.post("/api/v1/catalog/products", produto("Pastel", "Porções", "18", cozinha)));
        String foto = JsonPath.read(rita.upload(foto(pastel), TestImages.jpeg(300, 200), "pastel.jpg", "image/jpeg")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.photoFileId");

        Browser otto = login.inUnit(donoDaOutra, outraLoja);
        otto.get("/api/v1/catalog").andExpect(jsonPath("$.products.length()").value(0));
        otto.get("/api/v1/files/" + foto).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARQUIVO_NAO_ENCONTRADO"));
        otto.put(disponibilidade(pastel), "{\"available\":false}").andExpect(status().isNotFound());
        otto.put("/api/v1/catalog/products/" + pastel, produtoVersao("Pastel", "Porções", "1", null, 0))
                .andExpect(status().isNotFound());
        otto.upload(foto(pastel), TestImages.jpeg(100, 100), "x.jpg", "image/jpeg").andExpect(status().isNotFound());
        // Setor de outra unidade não serve de destino.
        otto.post("/api/v1/catalog/products", produto("Pastel", "Porções", "18", cozinha))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SETOR_INVALIDO"));
        assertThat(admin().queryForObject("SELECT available FROM fastpay.product WHERE id = ?::uuid", Boolean.class,
                pastel)).isTrue();
    }

    @Test
    void fotoDoProdutoIncluidaSubstituidaERemovida() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String pastel = id(rita.post("/api/v1/catalog/products", produto("Pastel", "Porções", "18", null)));
        byte[] original = TestImages.withExif(TestImages.jpeg(2400, 1200), 1, "GPS-LAT-23.55");
        String primeira = JsonPath.read(rita.upload(foto(pastel), original, "foto.png", "image/png")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.photoFileId");

        // Equipe da unidade baixa a imagem regravada: JPEG reduzido, sem os metadados do original.
        byte[] servida = login.inUnit(garcom, loja).get("/api/v1/files/" + primeira).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(servida, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("GPS-LAT");
        assertThat(admin().queryForMap("""
                SELECT width, height, original_content_type, purpose FROM fastpay.file_object WHERE id = ?::uuid
                """, primeira)).containsEntry("width", 1600).containsEntry("height", 800)
                .containsEntry("original_content_type", "image/jpeg").containsEntry("purpose", "FOTO_PRODUTO");

        String segunda = JsonPath.read(rita.upload(foto(pastel), TestImages.resource("amostra-com-perdas.webp"),
                "foto.webp", "image/webp").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.photoFileId");
        assertThat(segunda).isNotEqualTo(primeira);
        rita.get("/api/v1/files/" + primeira).andExpect(status().isNotFound());
        rita.get("/api/v1/files/" + segunda).andExpect(status().isOk());

        rita.delete(foto(pastel)).andExpect(status().isOk()).andExpect(jsonPath("$.photoFileId").value(nullValue()));
        rita.get("/api/v1/files/" + segunda).andExpect(status().isNotFound());
        assertThat(admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE subject_id = ?::uuid AND action LIKE 'produto.foto%'
                 ORDER BY occurred_at, action
                """, String.class, pastel)).containsExactlyInAnyOrder("produto.foto_incluida",
                "produto.foto_substituida", "produto.foto_removida");
    }

    @Test
    void imagemRecusadaPorConteudoTamanhoEDimensoes() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        String pastel = id(rita.post("/api/v1/catalog/products", produto("Pastel", "Porções", "18", null)));

        rita.upload(foto(pastel), "GIF89a....".getBytes(), "foto.jpg", "image/jpeg")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FORMATO_NAO_ACEITO"))
                .andExpect(jsonPath("$.detail").value("Formato não aceito. Envie uma imagem JPEG, PNG ou WebP."));
        rita.upload(foto(pastel), Arrays.copyOf(TestImages.jpeg(400, 400), 60), "foto.jpg", "image/jpeg")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGEM_INVALIDA"));
        rita.upload(foto(pastel), Arrays.copyOf(TestImages.jpeg(100, 100), 5 * 1024 * 1024 + 1), "foto.jpg",
                "image/jpeg").andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("ARQUIVO_GRANDE"))
                .andExpect(jsonPath("$.detail").value("A imagem passa de 5 MB. Envie um arquivo menor."));
        rita.upload(foto(pastel), TestImages.pngHeader(20_000, 20_000), "foto.png", "image/png")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGEM_GRANDE"));
        rita.upload(foto(pastel), TestImages.png(20, 20), "foto.png", "image/png")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGEM_PEQUENA"));
        rita.upload(foto(pastel), new byte[0], "foto.png", "image/png")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ARQUIVO_VAZIO"));

        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.file_object WHERE establishment_id = ?",
                Integer.class, loja)).isZero();
        assertThat(admin().queryForObject("SELECT photo_file_id FROM fastpay.product WHERE id = ?::uuid", UUID.class,
                pastel)).isNull();
    }

    @Test
    void logoIncluidoSubstituidoERemovidoComFallback() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        rita.get("/api/v1/establishment").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bar do Catálogo"))
                .andExpect(jsonPath("$.logoFileId").value(nullValue()));

        String logo = JsonPath.read(rita.upload("/api/v1/establishment/logo", TestImages.transparentPng(256, 256),
                "logo.png", "image/png").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.logoFileId");
        // Toda a equipe vê o logo da unidade; transparência preservada em PNG.
        Browser paulo = login.inUnit(producao, loja);
        paulo.get("/api/v1/establishment").andExpect(jsonPath("$.logoFileId").value(logo));
        paulo.get("/api/v1/files/" + logo).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        paulo.upload("/api/v1/establishment/logo", TestImages.png(100, 100), "logo.png", "image/png")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));
        login.inUnit(gerente, loja).delete("/api/v1/establishment/logo")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSAO_NEGADA"));

        String novo = JsonPath.read(rita.upload("/api/v1/establishment/logo", TestImages.jpeg(300, 300), "logo.jpg",
                "image/jpeg").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.logoFileId");
        rita.get("/api/v1/files/" + logo).andExpect(status().isNotFound());

        rita.delete("/api/v1/establishment/logo").andExpect(status().isOk())
                .andExpect(jsonPath("$.logoFileId").value(nullValue()));
        rita.get("/api/v1/files/" + novo).andExpect(status().isNotFound());
        rita.delete("/api/v1/establishment/logo").andExpect(status().isOk());
        assertThat(admin().queryForList("""
                SELECT action FROM fastpay.audit_event WHERE establishment_id = ? AND action LIKE 'logo.%'
                """, String.class, loja)).containsExactlyInAnyOrder("logo.incluido", "logo.substituido", "logo.removido");

        // Logo de outra unidade não aparece aqui.
        login.inUnit(donoDaOutra, outraLoja).get("/api/v1/files/" + novo).andExpect(status().isNotFound());
    }

    @Test
    void runtimeSemContextoNaoEnxergaCatalogo() throws Exception {
        Browser rita = login.inUnit(dono, loja);
        rita.post("/api/v1/catalog/products", produto("Pastel", "Porções", "18", null)).andExpect(status().isOk());
        var runtime = new org.springframework.jdbc.core.JdbcTemplate(dataSource(POSTGRES.getJdbcUrl(), "fastpay_app",
                appPassword()));
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.product", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.sector", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM fastpay.file_object", Integer.class)).isZero();
    }

    private static String produto(String name, String category, String price, String sectorId) {
        return "{\"name\":\"%s\",\"category\":\"%s\",\"price\":\"%s\",\"sectorId\":%s}".formatted(name, category,
                price, sectorId == null ? "null" : "\"" + sectorId + "\"");
    }

    private static String produtoVersao(String name, String category, String price, String sectorId, long version) {
        return "{\"name\":\"%s\",\"category\":\"%s\",\"price\":\"%s\",\"sectorId\":%s,\"version\":%d}".formatted(name,
                category, price, sectorId == null ? "null" : "\"" + sectorId + "\"", version);
    }

    private static String disponibilidade(String product) {
        return "/api/v1/catalog/products/" + product + "/availability";
    }

    private static String foto(String product) {
        return "/api/v1/catalog/products/" + product + "/photo";
    }

    private static String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }
}
