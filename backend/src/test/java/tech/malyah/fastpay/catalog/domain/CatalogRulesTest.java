package tech.malyah.fastpay.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import tech.malyah.fastpay.catalog.domain.CatalogRules.ProductFields;
import tech.malyah.fastpay.catalog.domain.CatalogRules.Rejected;
import tech.malyah.fastpay.catalog.domain.CatalogRules.Valid;

/** Preenchimento de produto e setor (H13, FR-13, FR-18). */
class CatalogRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"12;12.00", "12,5;12.50", "12,50;12.50", "12.50;12.50", "1.234,50;1234.50",
            "R$ 7,90;7.90", " 0,01 ;0.01", "99999,99;99999.99", "1.234;1234.00"})
    void precoEmReaisSemArredondar(String digitado, String esperado) {
        assertThat(CatalogRules.price(digitado)).contains(new BigDecimal(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0,00", "-1", "12,345", "12.345,678", "abc", "1e3", "100000", "12,", "1,2,3"})
    void precoInvalido(String digitado) {
        assertThat(CatalogRules.price(digitado)).isEmpty();
    }

    @Test
    void precoNulo() {
        assertThat(CatalogRules.price(null)).isEmpty();
    }

    @Test
    void produtoNormalizaEspacosEDescricaoVaziaViraNula() {
        CatalogRules.Checked<ProductFields> checked = CatalogRules.product("  Chopp   500 ml ", " Bebidas ", "   ",
                "14,90");
        assertThat(checked).isInstanceOf(Valid.class);
        ProductFields f = ((Valid<ProductFields>) checked).value();
        assertThat(f.name()).isEqualTo("Chopp 500 ml");
        assertThat(f.category()).isEqualTo("Bebidas");
        assertThat(f.description()).isNull();
        assertThat(f.price()).isEqualByComparingTo("14.90");
    }

    @Test
    void cadaCampoInvalidoApontaOCampo() {
        assertThat(field(CatalogRules.product(" ", "Bebidas", null, "1"))).isEqualTo("name");
        assertThat(field(CatalogRules.product("x".repeat(121), "Bebidas", null, "1"))).isEqualTo("name");
        assertThat(field(CatalogRules.product("Água", "", null, "1"))).isEqualTo("category");
        assertThat(field(CatalogRules.product("Água", "c".repeat(61), null, "1"))).isEqualTo("category");
        assertThat(field(CatalogRules.product("Água", "Bebidas", "d".repeat(501), "1"))).isEqualTo("description");
        assertThat(field(CatalogRules.product("Água", "Bebidas", null, "0"))).isEqualTo("price");
    }

    @Test
    void nomeDeSetor() {
        assertThat(CatalogRules.sectorName("  Cozinha  quente ")).isEqualTo(new Valid<>("Cozinha quente"));
        assertThat(field(CatalogRules.sectorName(""))).isEqualTo("name");
        assertThat(field(CatalogRules.sectorName(null))).isEqualTo("name");
        assertThat(field(CatalogRules.sectorName("s".repeat(61)))).isEqualTo("name");
    }

    private static String field(CatalogRules.Checked<?> checked) {
        assertThat(checked).isInstanceOf(Rejected.class);
        return ((Rejected<?>) checked).invalid().field();
    }
}
