package tech.malyah.fastpay.files;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/** Imagens sintéticas para os testes de envio (H13). */
public final class TestImages {

    private TestImages() {
    }

    /** Metade esquerda vermelha, direita azul. */
    public static BufferedImage halves(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(halves(width, height, false), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(halves(width, height, false), "png");
    }

    /** PNG com um canto transparente. */
    public static byte[] transparentPng(int width, int height) {
        BufferedImage image = halves(width, height, true);
        image.setRGB(0, 0, 0x00000000);
        return encode(image, "png");
    }

    public static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static byte[] resource(String name) {
        try (InputStream in = TestImages.class.getResourceAsStream("/imagens/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JPEG com um segmento APP1/Exif (big-endian) logo após o SOI, contendo a orientação e um texto qualquer
     * (simula metadados como GPS, que não podem sobreviver à regravação).
     */
    public static byte[] withExif(byte[] jpeg, int orientation, String marker) {
        byte[] text = marker.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + text.length);
        tiff.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(text);
        byte[] exif = ByteBuffer.allocate(6 + tiff.capacity()).put("Exif\0\0".getBytes(StandardCharsets.US_ASCII))
                .put(tiff.array()).array();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        int length = exif.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** Só a assinatura e o cabeçalho IHDR de um PNG com as dimensões dadas, sem dados de imagem válidos. */
    public static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        png.putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        // IDAT vazio e corrompido: nunca deve ser decodificado.
        png.putInt(0).put("IDAT".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        return png.array();
    }
}
