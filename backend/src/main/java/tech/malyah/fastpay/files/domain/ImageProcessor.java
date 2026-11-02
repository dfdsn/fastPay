package tech.malyah.fastpay.files.domain;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Verificação e reprocessamento de imagens enviadas (H13, FR-67, especificação §10, propostas T-03/T-04).
 *
 * <ol>
 * <li>Tamanho do arquivo até o limite (5 MB no PRD), antes de qualquer leitura.
 * <li>Formato pela assinatura do conteúdo (JPEG, PNG ou WebP); o resto é recusado.
 * <li>Dimensões lidas só do cabeçalho, antes de decodificar: recusa imagem pequena demais ou com lados/pixels
 * acima do limite (proteção contra "bomba de descompressão").
 * <li>Decodificação completa; arquivo truncado ou malformado é recusado.
 * <li>Regravação: giro EXIF aplicado, redução para o lado máximo de exibição, gravação nova em JPEG (ou PNG se houver
 * transparência). Metadados do original (EXIF, GPS, comentários, perfis, dados após o fim da imagem) não passam.
 * </ol>
 *
 * Só a imagem regravada é guardada. Regras puras, sem storage nem banco.
 */
public final class ImageProcessor {

    /**
     * Limites técnicos (T-03). {@code maxBytes}: arquivo enviado; {@code minSide}/{@code maxSide}: lados da imagem
     * enviada; {@code maxPixels}: largura × altura; {@code outputMaxSide}: maior lado da imagem guardada.
     */
    public record Limits(int maxBytes, int minSide, int maxSide, long maxPixels, int outputMaxSide, float jpegQuality) {
        public Limits {
            if (maxBytes <= 0 || minSide <= 0 || maxSide < minSide || maxPixels <= 0 || outputMaxSide <= 0
                    || jpegQuality <= 0 || jpegQuality > 1) {
                throw new IllegalArgumentException("Limites de imagem inválidos");
            }
        }
    }

    /** Motivo de recusa, estável para a API. */
    public enum Rejection {
        ARQUIVO_VAZIO,
        ARQUIVO_GRANDE,
        FORMATO_NAO_ACEITO,
        IMAGEM_INVALIDA,
        IMAGEM_PEQUENA,
        IMAGEM_GRANDE
    }

    public sealed interface Result {
    }

    /** Imagem pronta para guardar: bytes regravados, formato, dimensões finais e SHA-256 do que será guardado. */
    public record Processed(byte[] bytes, String contentType, ImageFormat original, int originalSize, int width,
            int height, String sha256) implements Result {
    }

    public record Rejected(Rejection reason) implements Result {
    }

    private ImageProcessor() {
    }

    public static Result process(byte[] input, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (input == null || input.length == 0) {
            return new Rejected(Rejection.ARQUIVO_VAZIO);
        }
        if (input.length > limits.maxBytes()) {
            return new Rejected(Rejection.ARQUIVO_GRANDE);
        }
        Optional<ImageFormat> format = ImageFormat.detect(input);
        if (format.isEmpty()) {
            return new Rejected(Rejection.FORMATO_NAO_ACEITO);
        }
        BufferedImage decoded;
        try {
            Decoded result = decode(input, format.get(), limits);
            if (result.rejection() != null) {
                return new Rejected(result.rejection());
            }
            decoded = result.image();
        } catch (IOException | RuntimeException malformed) {
            // Decodificadores lançam IOException e também exceções de tempo de execução em arquivo corrompido.
            return new Rejected(Rejection.IMAGEM_INVALIDA);
        }
        if (format.get() == ImageFormat.JPEG) {
            decoded = ExifOrientation.apply(decoded, ExifOrientation.read(input));
        }
        BufferedImage scaled = fit(decoded, limits.outputMaxSide());
        boolean transparent = hasTransparency(scaled);
        byte[] output;
        try {
            output = transparent ? png(scaled) : jpeg(scaled, limits.jpegQuality());
        } catch (IOException encoding) {
            return new Rejected(Rejection.IMAGEM_INVALIDA);
        }
        return new Processed(output, transparent ? "image/png" : "image/jpeg", format.get(), input.length,
                scaled.getWidth(), scaled.getHeight(), sha256(output));
    }

    private record Decoded(BufferedImage image, Rejection rejection) {
    }

    private static Decoded decode(byte[] input, ImageFormat format, Limits limits) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.readerName());
        if (!readers.hasNext()) {
            throw new IllegalStateException("Sem leitor ImageIO para " + format);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < limits.minSide() || height < limits.minSide()) {
                return new Decoded(null, Rejection.IMAGEM_PEQUENA);
            }
            if (width > limits.maxSide() || height > limits.maxSide() || (long) width * height > limits.maxPixels()) {
                return new Decoded(null, Rejection.IMAGEM_GRANDE);
            }
            ImageReadParam param = reader.getDefaultReadParam();
            // Imagem bem maior que a exibida: lê pulando pixels (menos memória), mantendo ao menos o dobro do final.
            int step = Math.max(1, Math.max(width, height) / (limits.outputMaxSide() * 2));
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new IOException("Imagem vazia");
            }
            return new Decoded(image, null);
        } finally {
            reader.dispose();
        }
    }

    /** Reduz para caber no lado máximo (nunca amplia), em etapas de metade para manter a qualidade. */
    static BufferedImage fit(BufferedImage image, int maxSide) {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = image;
        int cw = w;
        int ch = h;
        do {
            cw = Math.max(targetW, cw / 2);
            ch = Math.max(targetH, ch / 2);
            if (scale == 1.0) {
                cw = targetW;
                ch = targetH;
            }
            BufferedImage next = new BufferedImage(cw, ch, type);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(current, 0, 0, cw, ch, null);
            } finally {
                g.dispose();
            }
            current = next;
        } while (cw != targetW || ch != targetH);
        return current;
    }

    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Sem gravador PNG");
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
