package tech.malyah.fastpay.files;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.files.application.StoragePort;
import tech.malyah.fastpay.files.domain.ImageProcessor;
import tech.malyah.fastpay.files.infrastructure.JdbcFileStore;
import tech.malyah.fastpay.identity.Memberships;

/**
 * Imagens da unidade (H13, FR-67, especificação §10): foto de produto e logo. Quem envia chama
 * {@link #prepare(byte[])} fora da transação (verificação e regravação, ver {@code files.domain.ImageProcessor}) e
 * depois {@link #save} dentro da transação do comando, já autorizada e no contexto da unidade. Só a imagem regravada
 * é guardada, com chave aleatória; o download passa por {@link #read}, que confere o vínculo com a unidade.
 */
@Service
public class Images {

    private static final Logger log = LoggerFactory.getLogger(Images.class);

    /** Imagem verificada e regravada, pronta para {@link #save}. */
    public static final class Prepared {
        private final ImageProcessor.Processed processed;

        private Prepared(ImageProcessor.Processed processed) {
            this.processed = processed;
        }
    }

    public sealed interface Preparation {
    }

    public record Accepted(Prepared image) implements Preparation {
    }

    public record Refused(ImageRejection reason, String message) implements Preparation {
    }

    /** Conteúdo para download. */
    public record Content(byte[] bytes, String contentType, String sha256) {
    }

    private final ImageProcessor.Limits limits;
    private final StoragePort storage;
    private final JdbcFileStore store;
    private final Memberships memberships;
    private final Clock clock;

    Images(ImageProcessor.Limits limits, StoragePort storage, JdbcFileStore store, Memberships memberships,
            Clock clock) {
        this.limits = limits;
        this.storage = storage;
        this.store = store;
        this.memberships = memberships;
        this.clock = clock;
    }

    /** Verifica e regrava a imagem. Sem banco nem storage: chamar antes de abrir a transação do comando. */
    public Preparation prepare(byte[] upload) {
        return switch (ImageProcessor.process(upload, limits)) {
            case ImageProcessor.Processed processed -> new Accepted(new Prepared(processed));
            case ImageProcessor.Rejected rejected -> {
                ImageRejection reason = ImageRejection.valueOf(rejected.reason().name());
                yield new Refused(reason, message(reason));
            }
        };
    }

    /**
     * Guarda a imagem na unidade do contexto da transação corrente, em nome de {@code actorId}. Quem chama já
     * conferiu a permissão (o banco só aceita autor igual à identidade do contexto).
     *
     * @return id do arquivo
     */
    public UUID save(Prepared image, ImagePurpose purpose, UUID establishmentId, UUID actorId) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(purpose, "purpose");
        ImageProcessor.Processed p = image.processed;
        UUID id = UUID.randomUUID();
        String key = establishmentId + "/" + id;
        storage.put(key, p.bytes());
        store.insert(new JdbcFileStore.NewFile(id, purpose.name(), p.contentType(), p.original().mediaType(),
                p.originalSize(), p.bytes().length, p.sha256(), p.width(), p.height(), key, actorId, clock.instant()));
        log.info("Imagem {} ({}) guardada na unidade {} por {}", id, purpose, establishmentId, actorId);
        return id;
    }

    /**
     * Marca o arquivo como removido (deixa de ser servido), na transação corrente. Os bytes ficam no storage até
     * existir a política de retenção (T-04/P-08).
     */
    public boolean remove(UUID fileId, UUID actorId) {
        return fileId != null && store.remove(fileId, actorId, clock.instant());
    }

    /**
     * Imagem vigente da unidade para quem tem vínculo ativo nela. Arquivo de outra unidade, removido ou inexistente:
     * vazio, sem distinguir.
     */
    public Optional<Content> read(UUID identityId, UUID establishmentId, UUID fileId) {
        Optional<JdbcFileStore.Row> row = memberships.inUnit(identityId, establishmentId, membership -> store.active(fileId));
        return row.flatMap(r -> storage.get(r.storageKey()).map(bytes -> new Content(bytes, r.contentType(), r.sha256())));
    }

    /** Mensagem para a tela, com os limites configurados. */
    public String message(ImageRejection reason) {
        return switch (reason) {
            case ARQUIVO_VAZIO -> "Escolha uma imagem.";
            case ARQUIVO_GRANDE -> "A imagem passa de %d MB. Envie um arquivo menor."
                    .formatted(limits.maxBytes() / (1024 * 1024));
            case FORMATO_NAO_ACEITO -> "Formato não aceito. Envie uma imagem JPEG, PNG ou WebP.";
            case IMAGEM_INVALIDA -> "Não foi possível ler a imagem. O arquivo pode estar corrompido; tente outro.";
            case IMAGEM_PEQUENA -> "A imagem é pequena demais. Use pelo menos %d × %d pixels."
                    .formatted(limits.minSide(), limits.minSide());
            case IMAGEM_GRANDE -> "A imagem é grande demais. Use até %d pixels por lado e %d megapixels."
                    .formatted(limits.maxSide(), limits.maxPixels() / 1_000_000);
        };
    }
}
