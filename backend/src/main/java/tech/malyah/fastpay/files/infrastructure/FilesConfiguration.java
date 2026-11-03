package tech.malyah.fastpay.files.infrastructure;

import javax.imageio.ImageIO;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tech.malyah.fastpay.files.application.StoragePort;
import tech.malyah.fastpay.files.domain.ImageProcessor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class FilesConfiguration {

    FilesConfiguration() {
        // No jar do Spring Boot os leitores extras (WebP) só aparecem depois de um novo scan do ImageIO.
        ImageIO.scanForPlugins();
        ImageIO.setUseCache(false);
    }

    @Bean
    StoragePort storagePort(StorageProperties properties) {
        return new LocalFileStorage(properties.localDir());
    }

    @Bean
    ImageProcessor.Limits imageLimits(StorageProperties properties) {
        StorageProperties.Image image = properties.image();
        return new ImageProcessor.Limits(image.maxBytes(), image.minSide(), image.maxSide(), image.maxPixels(),
                image.outputMaxSide(), image.jpegQuality());
    }
}
