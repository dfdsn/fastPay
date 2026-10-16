package tech.malyah.fastpay.establishments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjTest {

    @ParameterizedTest
    @CsvSource({
            "11.222.333/0001-81, 11222333000181",
            "11222333000181, 11222333000181",
            " 11 222 333 0001 81 , 11222333000181",
            // Exemplo da Receita Federal para o CNPJ alfanumérico; minúsculas são aceitas.
            "12.ABC.345/01DE-35, 12ABC34501DE35",
            "12.abc.345/01de-35, 12ABC34501DE35"})
    void aceitaNumericoEAlfanumericoComOuSemPontuacao(String informado, String normalizado) {
        assertThat(Cnpj.normalize(informado)).hasValue(normalizado);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "11.222.333/0001-80", // primeiro verificador errado
            "11.222.333/0001-82", // segundo verificador errado
            "12.ABC.345/01DE-36",
            "12.ABC.345/01DE-3A", // verificador não numérico
            "1122233300018", // curto
            "112223330001811", // longo
            "00.000.000/0000-00", // repetido
            "11.222.333/0001_81"})
    void recusaInvalidos(String informado) {
        assertThat(Cnpj.normalize(informado)).isEmpty();
    }
}
