package tech.malyah.fastpay.files.domain;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Orientação EXIF de uma foto JPEG (tag 0x0112). Celulares gravam a foto "deitada" e só marcam o giro nos
 * metadados; como a imagem é regravada sem metadados, o giro é aplicado nos pixels. Leitura defensiva: qualquer
 * estrutura fora do esperado vale como orientação normal (1).
 */
public final class ExifOrientation {

    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    /** Orientação de 1 a 8 lida do segmento APP1/Exif do JPEG; 1 se não houver ou se estiver malformada. */
    public static int read(byte[] jpeg) {
        try {
            return readUnchecked(jpeg);
        } catch (IndexOutOfBoundsException malformed) {
            return 1;
        }
    }

    private static int readUnchecked(byte[] b) {
        int pos = 2; // depois do SOI (FF D8)
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) { // início dos dados da imagem ou fim
                return 1;
            }
            int length = u16(b, pos + 2, true);
            if (length < 2) {
                return 1;
            }
            int data = pos + 4;
            if (marker == 0xE1 && length >= 8 && b[data] == 'E' && b[data + 1] == 'x' && b[data + 2] == 'i'
                    && b[data + 3] == 'f' && b[data + 4] == 0 && b[data + 5] == 0) {
                return fromTiff(b, data + 6, data + length - 2);
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static int fromTiff(byte[] b, int tiff, int end) {
        boolean bigEndian;
        if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            bigEndian = true;
        } else if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            bigEndian = false;
        } else {
            return 1;
        }
        if (u16(b, tiff + 2, bigEndian) != 42) {
            return 1;
        }
        long ifdOffset = u32(b, tiff + 4, bigEndian);
        if (ifdOffset < 8 || tiff + ifdOffset + 2 > end) {
            return 1;
        }
        int ifd = tiff + (int) ifdOffset;
        int entries = u16(b, ifd, bigEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, entry, bigEndian) == ORIENTATION_TAG) {
                int value = u16(b, entry + 8, bigEndian);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    /** Aplica a orientação aos pixels; 1 (ou valor desconhecido) devolve a mesma imagem. */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform t;
        boolean swap = orientation >= 5 && orientation <= 8;
        switch (orientation) {
            case 2 -> t = new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> t = new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> t = new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> t = new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> t = new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> t = new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> t = new AffineTransform(0, -1, 1, 0, 0, w);
            default -> {
                return image;
            }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, image.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int u16(byte[] b, int at, boolean bigEndian) {
        int hi = b[at] & 0xFF;
        int lo = b[at + 1] & 0xFF;
        return bigEndian ? (hi << 8) | lo : (lo << 8) | hi;
    }

    private static long u32(byte[] b, int at, boolean bigEndian) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int byteValue = b[at + (bigEndian ? i : 3 - i)] & 0xFF;
            v = (v << 8) | byteValue;
        }
        return v;
    }
}
