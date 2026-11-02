package tech.malyah.fastpay.files.infrastructure;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Armazenamento e limites das imagens (H13). {@code localDir}: pasta do storage local (dev/HML); os demais são os
 * limites técnicos de imagem propostos em T-03 (ver {@code files.domain.ImageProcessor.Limits}).
 */
@ConfigurationProperties("fastpay.storage")
public record StorageProperties(Path localDir, Image image) {

    public record Image(int maxBytes, int minSide, int maxSide, long maxPixels, int outputMaxSide, float jpegQuality) {
    }
}
