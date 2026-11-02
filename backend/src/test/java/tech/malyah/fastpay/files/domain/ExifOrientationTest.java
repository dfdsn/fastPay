package tech.malyah.fastpay.files.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.files.TestImages;

/** Leitura defensiva da orientação EXIF e giro dos pixels (H13). */
class ExifOrientationTest {

    @Test
    void semExifOuMalformadoValeOrientacaoNormal() {
        assertThat(ExifOrientation.read(TestImages.jpeg(80, 80))).isEqualTo(1);
        byte[] truncado = java.util.Arrays.copyOf(TestImages.withExif(TestImages.jpeg(80, 80), 6, ""), 20);
        assertThat(ExifOrientation.read(truncado)).isEqualTo(1);
        assertThat(ExifOrientation.read(TestImages.withExif(TestImages.jpeg(80, 80), 42, ""))).isEqualTo(1);
    }

    @Test
    void leAOrientacaoGravada() {
        for (int o = 1; o <= 8; o++) {
            assertThat(ExifOrientation.read(TestImages.withExif(TestImages.jpeg(80, 80), o, ""))).isEqualTo(o);
        }
    }

    /** Pixel (0,0) marcado; depois do giro ele deve estar no canto esperado. */
    @ParameterizedTest
    @CsvSource({"1,0,0,4,2", "2,3,0,4,2", "3,3,1,4,2", "4,0,1,4,2", "5,0,0,2,4", "6,1,0,2,4", "7,1,3,2,4",
            "8,0,3,2,4"})
    void giraCadaOrientacao(int orientation, int x, int y, int width, int height) {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFFFFFF);
        BufferedImage out = ExifOrientation.apply(image, orientation);
        assertThat(out.getWidth()).isEqualTo(width);
        assertThat(out.getHeight()).isEqualTo(height);
        assertThat(out.getRGB(x, y) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }
}
