package tech.malyah.fastpay.establishments.application;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.establishments.infrastructure.JdbcEstablishmentStore;
import tech.malyah.fastpay.files.ImagePurpose;
import tech.malyah.fastpay.files.ImageRejection;
import tech.malyah.fastpay.files.Images;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;

/**
 * Identidade visual da unidade (H13, FR-67, D-27): logo opcional, incluído, substituído ou removido pelo
 * administrador do estabelecimento ({@link Permission#CONFIGURAR_ESTABELECIMENTO}). Sem logo, as telas mostram o nome
 * e as iniciais. Qualquer pessoa com vínculo ativo vê o nome e o logo da unidade.
 */
@Service
public class Branding {

    private static final Logger log = LoggerFactory.getLogger(Branding.class);

    /** {@code logoFileId} vazio: sem logo (usar nome/iniciais). */
    public record View(UUID establishmentId, String name, UUID logoFileId) {
    }

    public sealed interface Outcome {
    }

    public record Done(View view) implements Outcome {
    }

    public record ImageRefused(ImageRejection reason, String message) implements Outcome {
    }

    private final Memberships memberships;
    private final JdbcEstablishmentStore store;
    private final Images images;

    Branding(Memberships memberships, JdbcEstablishmentStore store, Images images) {
        this.memberships = memberships;
        this.store = store;
        this.images = images;
    }

    public View view(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, me -> view(store.branding(false)));
    }

    /** Inclui ou substitui o logo; a imagem é verificada e regravada antes da transação. */
    public Outcome setLogo(UUID actorId, UUID establishmentId, byte[] upload, String correlationId) {
        Images.Preparation preparation = images.prepare(upload);
        if (preparation instanceof Images.Refused refused) {
            return new ImageRefused(refused.reason(), refused.message());
        }
        Images.Prepared image = ((Images.Accepted) preparation).image();
        return memberships.inUnit(actorId, establishmentId, Permission.CONFIGURAR_ESTABELECIMENTO, me -> {
            UUID previous = store.branding(true).orElseThrow().logoFileId();
            UUID fileId = images.save(image, ImagePurpose.LOGO, establishmentId, actorId);
            images.remove(previous, actorId);
            store.setLogo(fileId);
            store.audit(previous == null ? "logo.incluido" : "logo.substituido", fileId, actorId, correlationId);
            log.info("Logo da unidade {} {} por {}", establishmentId, previous == null ? "incluído" : "substituído",
                    actorId);
            return new Done(view(store.branding(false)));
        });
    }

    public Outcome removeLogo(UUID actorId, UUID establishmentId, String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.CONFIGURAR_ESTABELECIMENTO, me -> {
            UUID previous = store.branding(true).orElseThrow().logoFileId();
            if (previous != null) {
                images.remove(previous, actorId);
                store.setLogo(null);
                store.audit("logo.removido", previous, actorId, correlationId);
            }
            return new Done(view(store.branding(false)));
        });
    }

    private static View view(Optional<JdbcEstablishmentStore.Branding> row) {
        JdbcEstablishmentStore.Branding b = row.orElseThrow();
        return new View(b.id(), b.name(), b.logoFileId());
    }
}
