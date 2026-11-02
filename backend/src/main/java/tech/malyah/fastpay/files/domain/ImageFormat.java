package tech.malyah.fastpay.files.domain;

import java.util.Optional;

/**
 * Formatos de imagem aceitos no envio (FR-67: JPEG, PNG ou WebP), reconhecidos pela assinatura no início do
 * arquivo. Nome do arquivo e Content-Type informados pelo navegador são ignorados (especificação §10).
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpeg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mediaType;
    private final String readerName;

    ImageFormat(String mediaType, String readerName) {
        this.mediaType = mediaType;
        this.readerName = readerName;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Nome do formato no ImageIO. */
    public String readerName() {
        return readerName;
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Formato pela assinatura; vazio para qualquer outro conteúdo (GIF, SVG, PDF, HTML, executável...). */
    public static Optional<ImageFormat> detect(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, PNG_SIGNATURE, 0)) {
            return Optional.of(PNG);
        }
        if (bytes.length >= 12 && startsWith(bytes, "RIFF".getBytes(), 0) && startsWith(bytes, "WEBP".getBytes(), 8)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix, int offset) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
