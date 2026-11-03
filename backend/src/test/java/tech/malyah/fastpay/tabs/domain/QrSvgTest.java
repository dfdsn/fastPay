package tech.malyah.fastpay.tabs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QrSvgTest {

    @Test
    void svgSoComFormasSemOTextoDoLink() {
        String link = "https://fastpay.malyah.tech/c#" + AccessTokens.generate();
        String svg = QrSvg.of(link);
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"").endsWith("</svg>")
                .contains("<path d=\"M").doesNotContain(link).doesNotContain("<script").doesNotContain("<text");
        // Mesmo link, mesmo desenho; links diferentes, desenhos diferentes.
        assertThat(QrSvg.of(link)).isEqualTo(svg);
        assertThat(QrSvg.of(link + "x")).isNotEqualTo(svg);
    }
}
