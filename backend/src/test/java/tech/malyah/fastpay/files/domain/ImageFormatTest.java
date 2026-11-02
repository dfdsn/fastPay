package tech.malyah.fastpay.files.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.files.TestImages;

class ImageFormatTest {

    @Test
    void reconheceAssinaturas() {
        assertThat(ImageFormat.detect(TestImages.jpeg(10, 10))).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(TestImages.png(10, 10))).contains(ImageFormat.PNG);
        assertThat(ImageFormat.detect(TestImages.resource("amostra-com-perdas.webp"))).contains(ImageFormat.WEBP);
        assertThat(ImageFormat.detect(null)).isEmpty();
        assertThat(ImageFormat.detect(new byte[] {(byte) 0xFF})).isEmpty();
        assertThat(ImageFormat.detect("RIFF0000WAVE".getBytes())).isEmpty();
        assertThat(ImageFormat.JPEG.mediaType()).isEqualTo("image/jpeg");
    }
}
