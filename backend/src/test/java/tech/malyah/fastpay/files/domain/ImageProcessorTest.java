package tech.malyah.fastpay.files.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tech.malyah.fastpay.files.TestImages;
import tech.malyah.fastpay.files.domain.ImageProcessor.Processed;
import tech.malyah.fastpay.files.domain.ImageProcessor.Rejected;
import tech.malyah.fastpay.files.domain.ImageProcessor.Rejection;

/**
 * Verificação e regravação de imagens (H13, FR-67, propostas T-03/T-04): formato pelo conteúdo, limites antes de
 * decodificar, arquivo malformado recusado e nada do original (metadados, carga extra) passando para o que é
 * guardado.
 */
class ImageProcessorTest {

    static final ImageProcessor.Limits LIMITES = new ImageProcessor.Limits(5 * 1024 * 1024, 64, 8000, 40_000_000,
            1600, 0.85f);

    @BeforeAll
    static void plugins() {
        ImageIO.scanForPlugins();
    }

    @Test
    void jpegValidoERegravadoEmJpeg() throws IOException {
        Processed p = processed(TestImages.jpeg(320, 200));
        assertThat(p.contentType()).isEqualTo("image/jpeg");
        assertThat(p.original()).isEqualTo(ImageFormat.JPEG);
        assertThat(p.width()).isEqualTo(320);
        assertThat(p.height()).isEqualTo(200);
        assertThat(p.sha256()).matches("[0-9a-f]{64}");
        assertThat(read(p.bytes()).getWidth()).isEqualTo(320);
    }

    @Test
    void pngOpacoViraJpegEPngTransparentePermanecePng() {
        assertThat(processed(TestImages.png(100, 100)).contentType()).isEqualTo("image/jpeg");
        Processed transparente = processed(TestImages.transparentPng(100, 100));
        assertThat(transparente.contentType()).isEqualTo("image/png");
        assertThat(transparente.original()).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void webpComPerdasESemPerdasSaoAceitos() throws IOException {
        Processed opaco = processed(TestImages.resource("amostra-com-perdas.webp"));
        assertThat(opaco.original()).isEqualTo(ImageFormat.WEBP);
        assertThat(opaco.contentType()).isEqualTo("image/jpeg");
        assertThat(opaco.width()).isEqualTo(200);
        assertThat(opaco.height()).isEqualTo(120);

        Processed transparente = processed(TestImages.resource("amostra-transparente.webp"));
        assertThat(transparente.contentType()).isEqualTo("image/png");
        assertThat(read(transparente.bytes()).getRGB(0, 0) >>> 24).isZero();
    }

    @Test
    void imagemGrandeEReduzidaParaOLadoMaximo() {
        Processed p = processed(TestImages.jpeg(4000, 1000));
        assertThat(p.width()).isEqualTo(1600);
        assertThat(p.height()).isEqualTo(400);
    }

    @Test
    void formatoEPeloConteudoNaoPeloNome() {
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] html = "<html><body>oi</body></html>".getBytes(StandardCharsets.UTF_8);
        for (byte[] content : new byte[][] {gif, svg, pdf, html}) {
            assertThat(rejection(content)).isEqualTo(Rejection.FORMATO_NAO_ACEITO);
        }
    }

    @Test
    void vazioETamanhoAcimaDoLimite() {
        assertThat(rejection(new byte[0])).isEqualTo(Rejection.ARQUIVO_VAZIO);
        assertThat(rejection(null)).isEqualTo(Rejection.ARQUIVO_VAZIO);
        byte[] grande = Arrays.copyOf(TestImages.jpeg(100, 100), LIMITES.maxBytes() + 1);
        assertThat(rejection(grande)).isEqualTo(Rejection.ARQUIVO_GRANDE);
    }

    @Test
    void arquivoTruncadoOuCorrompidoERecusado() {
        byte[] jpeg = TestImages.jpeg(300, 300);
        assertThat(rejection(Arrays.copyOf(jpeg, 40))).isEqualTo(Rejection.IMAGEM_INVALIDA);
        byte[] lixo = new byte[2000];
        lixo[0] = (byte) 0xFF;
        lixo[1] = (byte) 0xD8;
        lixo[2] = (byte) 0xFF;
        assertThat(rejection(lixo)).isEqualTo(Rejection.IMAGEM_INVALIDA);
        byte[] webpFalso = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 lixo".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(rejection(webpFalso)).isEqualTo(Rejection.IMAGEM_INVALIDA);
    }

    @Test
    void dimensoesSaoConferidasNoCabecalhoAntesDeDecodificar() {
        // 50.000 × 50.000 declarados em poucos bytes: recusado sem tentar alocar 10 GB.
        assertThat(rejection(TestImages.pngHeader(50_000, 50_000))).isEqualTo(Rejection.IMAGEM_GRANDE);
        assertThat(rejection(TestImages.pngHeader(8001, 100))).isEqualTo(Rejection.IMAGEM_GRANDE);
        assertThat(rejection(TestImages.pngHeader(7000, 7000))).isEqualTo(Rejection.IMAGEM_GRANDE);
        assertThat(rejection(TestImages.png(63, 200))).isEqualTo(Rejection.IMAGEM_PEQUENA);
    }

    @Test
    void metadadosECargaExtraDoOriginalNaoPassam() {
        byte[] comExif = TestImages.withExif(TestImages.jpeg(200, 200), 1, "GPS-LAT-23.5505-LON-46.6333");
        byte[] poliglota = concat(comExif, "<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII));
        Processed p = processed(poliglota);
        String guardado = new String(p.bytes(), StandardCharsets.ISO_8859_1);
        assertThat(guardado).doesNotContain("GPS-LAT").doesNotContain("<script>").doesNotContain("Exif");
    }

    @Test
    void orientacaoExifEAplicadaNosPixels() throws IOException {
        // Orientação 6: girar 90° no sentido horário; a metade esquerda (vermelha) passa a ficar em cima.
        Processed p = processed(TestImages.withExif(TestImages.jpeg(200, 100), 6, ""));
        assertThat(p.width()).isEqualTo(100);
        assertThat(p.height()).isEqualTo(200);
        BufferedImage girada = read(p.bytes());
        assertThat(red(girada.getRGB(50, 20))).isGreaterThan(200);
        assertThat(blue(girada.getRGB(50, 180))).isGreaterThan(200);
    }

    @Test
    void limitesIncoerentesNaoSobem() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ImageProcessor.Limits(0, 1, 1, 1, 1, 0.5f))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ImageProcessor.Limits(1, 10, 5, 1, 1, 0.5f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Processed processed(byte[] content) {
        ImageProcessor.Result result = ImageProcessor.process(content, LIMITES);
        assertThat(result).isInstanceOf(Processed.class);
        return (Processed) result;
    }

    private static Rejection rejection(byte[] content) {
        ImageProcessor.Result result = ImageProcessor.process(content, LIMITES);
        assertThat(result).isInstanceOf(Rejected.class);
        return ((Rejected) result).reason();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
