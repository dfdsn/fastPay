package tech.malyah.fastpay.catalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.catalog.domain.CatalogRules;
import tech.malyah.fastpay.catalog.domain.CatalogRules.ProductFields;
import tech.malyah.fastpay.catalog.infrastructure.JdbcCatalogStore;
import tech.malyah.fastpay.catalog.infrastructure.JdbcCatalogStore.ProductRow;
import tech.malyah.fastpay.catalog.infrastructure.JdbcCatalogStore.SectorRow;
import tech.malyah.fastpay.files.ImagePurpose;
import tech.malyah.fastpay.files.ImageRejection;
import tech.malyah.fastpay.files.Images;
import tech.malyah.fastpay.identity.Memberships;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.UnitMembership;

/**
 * Catálogo da unidade (H13, FR-13, FR-14, FR-18, FR-67). O administrador do estabelecimento cadastra, altera e
 * arquiva produtos e setores e troca ou remove a foto ({@link Permission#GERIR_CATALOGO}); quem tem
 * {@link Permission#ALTERAR_DISPONIBILIDADE} (gerente, administrador ou garçom com a permissão delegada) marca
 * disponível ou esgotado. Qualquer pessoa com vínculo ativo consulta o catálogo; o preço só aparece para quem opera
 * comanda (produção não tem visão financeira).
 *
 * <p>Toda mudança roda na unidade da sessão, com a permissão conferida na mesma transação, a linha travada e a
 * versão esperada (quem editou por último não sobrescreve sem saber), e grava auditoria. Arquivar preserva a linha:
 * pedidos guardam o preço do envio (FR-16) e a produção mantém o setor de origem. Disponibilidade é conferida de
 * novo no envio do pedido (H16).
 */
@Service
public class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);

    /** Produtos por página na consulta (D-13). */
    public static final int PAGE_SIZE = 100;

    public record Sector(UUID id, String name, long version) {
    }

    /** {@code price} vazio para quem não vê valores (produção). {@code sectorId} vazio: dispensa produção. */
    public record Product(UUID id, String name, String category, String description, BigDecimal price,
            UUID sectorId, boolean available, UUID photoFileId, long version) {
    }

    public record Listing(List<Sector> sectors, List<Product> products, List<String> categories, int page,
            int pageSize, long totalProducts, boolean pricesVisible) {
    }

    /** O que a pessoa digitou para o produto; {@code price} como texto, sem arredondar. */
    public record ProductInput(String name, String category, String description, String price, UUID sectorId,
            boolean available) {
    }

    public enum Refusal {
        NAO_ENCONTRADO,
        /** Outra pessoa alterou antes; recarregar e conferir. */
        VERSAO_DESATUALIZADA,
        /** Já existe outro ativo com o mesmo nome na unidade. */
        NOME_EM_USO,
        /** Setor inexistente, arquivado ou de outra unidade. */
        SETOR_INVALIDO,
        /** Setor com produtos ativos não é arquivado. */
        SETOR_EM_USO
    }

    public sealed interface Outcome<T> {
    }

    public record Done<T>(T value) implements Outcome<T> {
    }

    public record Refused<T>(Refusal refusal) implements Outcome<T> {
    }

    public record Invalid<T>(String field, String message) implements Outcome<T> {
    }

    public record ImageRefused<T>(ImageRejection reason, String message) implements Outcome<T> {
    }

    private final Memberships memberships;
    private final JdbcCatalogStore store;
    private final Images images;
    private final Clock clock;

    Catalog(Memberships memberships, JdbcCatalogStore store, Images images, Clock clock) {
        this.memberships = memberships;
        this.store = store;
        this.images = images;
        this.clock = clock;
    }

    /** Setores ativos e uma página de produtos ativos, por categoria e nome. */
    public Listing listing(UUID actorId, UUID establishmentId, int page) {
        int p = Math.max(0, page);
        return memberships.inUnit(actorId, establishmentId, me -> {
            boolean prices = me.has(Permission.OPERAR_COMANDA);
            List<Sector> sectors = store.activeSectors().stream().map(Catalog::sector).toList();
            List<Product> products = store.activeProducts(p * PAGE_SIZE, PAGE_SIZE).stream()
                    .map(row -> product(row, prices)).toList();
            return new Listing(sectors, products, store.activeCategories(), p, PAGE_SIZE, store.countActiveProducts(),
                    prices);
        });
    }

    /** Produto como o pedido o captura no envio (H16): preço vigente, setor de preparo e disponibilidade. */
    public record OrderableProduct(UUID id, String name, BigDecimal price, UUID sectorId, boolean available) {
    }

    /**
     * Produtos ativos da unidade entre os pedidos, na transação de unidade de quem chama (H16, FR-14, FR-16), travados
     * para leitura até o commit: uma troca de preço ou de disponibilidade simultânea espera o envio terminar ou é
     * vista por ele. Arquivado ou de outra unidade não aparece (o pedido trata como indisponível). A permissão é
     * conferida por quem chama.
     */
    public java.util.Map<UUID, OrderableProduct> forOrderInCurrentUnit(java.util.Collection<UUID> productIds) {
        java.util.Map<UUID, OrderableProduct> found = new java.util.LinkedHashMap<>();
        for (ProductRow row : store.shareActiveProducts(productIds)) {
            found.put(row.id(), new OrderableProduct(row.id(), row.name(), row.price(), row.sectorId(),
                    row.available()));
        }
        return found;
    }

    // Setores

    public Outcome<Sector> createSector(UUID actorId, UUID establishmentId, String name, String correlationId) {
        CatalogRules.Checked<String> checked = CatalogRules.sectorName(name);
        if (checked instanceof CatalogRules.Rejected<String> r) {
            return new Invalid<>(r.invalid().field(), r.invalid().message());
        }
        String valid = ((CatalogRules.Valid<String>) checked).value();
        return manage(actorId, establishmentId, me -> {
            if (store.activeSectorNamed(valid, null)) {
                return new Refused<>(Refusal.NOME_EM_USO);
            }
            UUID id = UUID.randomUUID();
            store.insertSector(id, valid, actorId, clock.instant());
            store.audit("setor.criado", id, actorId, null, correlationId);
            return new Done<>(sector(store.activeSector(id).orElseThrow()));
        });
    }

    public Outcome<Sector> renameSector(UUID actorId, UUID establishmentId, UUID sectorId, long version, String name,
            String correlationId) {
        CatalogRules.Checked<String> checked = CatalogRules.sectorName(name);
        if (checked instanceof CatalogRules.Rejected<String> r) {
            return new Invalid<>(r.invalid().field(), r.invalid().message());
        }
        String valid = ((CatalogRules.Valid<String>) checked).value();
        return manage(actorId, establishmentId, me -> {
            Optional<SectorRow> current = store.lockActiveSector(sectorId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            if (current.get().version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            if (current.get().name().equals(valid)) {
                return new Done<>(sector(current.get()));
            }
            if (store.activeSectorNamed(valid, sectorId)) {
                return new Refused<>(Refusal.NOME_EM_USO);
            }
            store.renameSector(sectorId, version, valid, actorId, clock.instant());
            store.audit("setor.renomeado", sectorId, actorId, null, correlationId);
            return new Done<>(sector(store.activeSector(sectorId).orElseThrow()));
        });
    }

    /** Arquiva o setor; recusa enquanto houver produto ativo encaminhado a ele. */
    public Outcome<Sector> archiveSector(UUID actorId, UUID establishmentId, UUID sectorId, long version,
            String correlationId) {
        return manage(actorId, establishmentId, me -> {
            Optional<SectorRow> current = store.lockActiveSector(sectorId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            if (current.get().version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            if (store.activeProductsInSector(sectorId) > 0) {
                return new Refused<>(Refusal.SETOR_EM_USO);
            }
            store.archiveSector(sectorId, version, actorId, clock.instant());
            store.audit("setor.arquivado", sectorId, actorId, null, correlationId);
            return new Done<>(new Sector(sectorId, current.get().name(), version + 1));
        });
    }

    // Produtos

    public Outcome<Product> createProduct(UUID actorId, UUID establishmentId, ProductInput input,
            String correlationId) {
        Objects.requireNonNull(input, "input");
        CatalogRules.Checked<ProductFields> checked = CatalogRules.product(input.name(), input.category(),
                input.description(), input.price());
        if (checked instanceof CatalogRules.Rejected<ProductFields> r) {
            return new Invalid<>(r.invalid().field(), r.invalid().message());
        }
        ProductFields f = ((CatalogRules.Valid<ProductFields>) checked).value();
        return manage(actorId, establishmentId, me -> {
            if (input.sectorId() != null && store.lockActiveSector(input.sectorId()).isEmpty()) {
                return new Refused<>(Refusal.SETOR_INVALIDO);
            }
            if (store.activeProductNamed(f.name(), null)) {
                return new Refused<>(Refusal.NOME_EM_USO);
            }
            UUID id = UUID.randomUUID();
            store.insertProduct(id, f.name(), f.category(), f.description(), f.price(), input.sectorId(),
                    input.available(), actorId, clock.instant());
            store.audit("produto.criado", id, actorId, "preco " + f.price().toPlainString(), correlationId);
            log.info("Produto {} criado na unidade {} por {}", id, establishmentId, actorId);
            return new Done<>(product(store.activeProduct(id).orElseThrow(), true));
        });
    }

    /** Altera nome, categoria, descrição, preço e setor. Não muda pedidos já enviados (FR-16). */
    public Outcome<Product> updateProduct(UUID actorId, UUID establishmentId, UUID productId, long version,
            ProductInput input, String correlationId) {
        Objects.requireNonNull(input, "input");
        CatalogRules.Checked<ProductFields> checked = CatalogRules.product(input.name(), input.category(),
                input.description(), input.price());
        if (checked instanceof CatalogRules.Rejected<ProductFields> r) {
            return new Invalid<>(r.invalid().field(), r.invalid().message());
        }
        ProductFields f = ((CatalogRules.Valid<ProductFields>) checked).value();
        return manage(actorId, establishmentId, me -> {
            Optional<ProductRow> current = store.lockActiveProduct(productId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            ProductRow before = current.get();
            if (before.version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            if (input.sectorId() != null && store.lockActiveSector(input.sectorId()).isEmpty()) {
                return new Refused<>(Refusal.SETOR_INVALIDO);
            }
            if (!before.name().equalsIgnoreCase(f.name()) && store.activeProductNamed(f.name(), productId)) {
                return new Refused<>(Refusal.NOME_EM_USO);
            }
            String changes = changes(before, f, input.sectorId());
            if (changes.isEmpty()) {
                return new Done<>(product(before, true));
            }
            store.updateProduct(productId, version, f.name(), f.category(), f.description(), f.price(),
                    input.sectorId(), actorId, clock.instant());
            store.audit("produto.alterado", productId, actorId, changes, correlationId);
            return new Done<>(product(store.activeProduct(productId).orElseThrow(), true));
        });
    }

    public Outcome<Product> archiveProduct(UUID actorId, UUID establishmentId, UUID productId, long version,
            String correlationId) {
        return manage(actorId, establishmentId, me -> {
            Optional<ProductRow> current = store.lockActiveProduct(productId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            if (current.get().version() != version) {
                return new Refused<>(Refusal.VERSAO_DESATUALIZADA);
            }
            store.archiveProduct(productId, version, actorId, clock.instant());
            store.audit("produto.arquivado", productId, actorId, null, correlationId);
            ProductRow row = current.get();
            return new Done<>(new Product(row.id(), row.name(), row.category(), row.description(), row.price(),
                    row.sectorId(), row.available(), row.photoFileId(), version + 1));
        });
    }

    /**
     * Marca disponível ou esgotado (FR-14). Repetir o mesmo valor não muda nada. Pedidos já enviados não são
     * cancelados; o envio de um rascunho confere a disponibilidade de novo (H16).
     */
    public Outcome<Product> setAvailability(UUID actorId, UUID establishmentId, UUID productId, boolean available,
            String correlationId) {
        return memberships.inUnit(actorId, establishmentId, Permission.ALTERAR_DISPONIBILIDADE, me -> {
            Optional<ProductRow> current = store.lockActiveProduct(productId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            boolean prices = me.has(Permission.OPERAR_COMANDA);
            if (current.get().available() == available) {
                return new Done<>(product(current.get(), prices));
            }
            store.setAvailability(productId, available, actorId, clock.instant());
            store.audit(available ? "produto.disponivel" : "produto.esgotado", productId, actorId, null,
                    correlationId);
            return new Done<>(product(store.activeProduct(productId).orElseThrow(), prices));
        });
    }

    /**
     * Inclui ou substitui a foto do produto (uma por produto). A imagem é verificada e regravada antes da transação;
     * a anterior deixa de ser servida.
     */
    public Outcome<Product> setPhoto(UUID actorId, UUID establishmentId, UUID productId, byte[] upload,
            String correlationId) {
        Images.Preparation preparation = images.prepare(upload);
        if (preparation instanceof Images.Refused refused) {
            return new ImageRefused<>(refused.reason(), refused.message());
        }
        Images.Prepared image = ((Images.Accepted) preparation).image();
        return manage(actorId, establishmentId, me -> {
            Optional<ProductRow> current = store.lockActiveProduct(productId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            UUID fileId = images.save(image, ImagePurpose.FOTO_PRODUTO, establishmentId, actorId);
            images.remove(current.get().photoFileId(), actorId);
            store.setPhoto(productId, fileId, actorId, clock.instant());
            store.audit(current.get().photoFileId() == null ? "produto.foto_incluida" : "produto.foto_substituida",
                    productId, actorId, null, correlationId);
            return new Done<>(product(store.activeProduct(productId).orElseThrow(), true));
        });
    }

    public Outcome<Product> removePhoto(UUID actorId, UUID establishmentId, UUID productId, String correlationId) {
        return manage(actorId, establishmentId, me -> {
            Optional<ProductRow> current = store.lockActiveProduct(productId);
            if (current.isEmpty()) {
                return new Refused<>(Refusal.NAO_ENCONTRADO);
            }
            if (current.get().photoFileId() == null) {
                return new Done<>(product(current.get(), true));
            }
            images.remove(current.get().photoFileId(), actorId);
            store.setPhoto(productId, null, actorId, clock.instant());
            store.audit("produto.foto_removida", productId, actorId, null, correlationId);
            return new Done<>(product(store.activeProduct(productId).orElseThrow(), true));
        });
    }

    /**
     * Mudança de catálogo pelo administrador. Nome repetido em duas gravações simultâneas é barrado pelo índice
     * único e vira a mesma recusa da verificação.
     */
    private <T> Outcome<T> manage(UUID actorId, UUID establishmentId, Function<UnitMembership, Outcome<T>> work) {
        return uniqueName(() -> memberships.inUnit(actorId, establishmentId, Permission.GERIR_CATALOGO, work));
    }

    private static <T> Outcome<T> uniqueName(Supplier<Outcome<T>> work) {
        try {
            return work.get();
        } catch (DuplicateKeyException sameName) {
            return new Refused<>(Refusal.NOME_EM_USO);
        }
    }

    /** Campos alterados, sem texto livre na auditoria; o preço registra antes e depois. */
    private static String changes(ProductRow before, ProductFields after, UUID sectorId) {
        List<String> changed = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            changed.add("nome");
        }
        if (!before.category().equals(after.category())) {
            changed.add("categoria");
        }
        if (!Objects.equals(before.description(), after.description())) {
            changed.add("descricao");
        }
        if (!Objects.equals(before.sectorId(), sectorId)) {
            changed.add("setor");
        }
        if (before.price().compareTo(after.price()) != 0) {
            changed.add("preco " + before.price().toPlainString() + ">" + after.price().toPlainString());
        }
        return String.join("; ", changed);
    }

    private static Sector sector(SectorRow row) {
        return new Sector(row.id(), row.name(), row.version());
    }

    private static Product product(ProductRow row, boolean prices) {
        return new Product(row.id(), row.name(), row.category(), row.description(), prices ? row.price() : null,
                row.sectorId(), row.available(), row.photoFileId(), row.version());
    }
}
