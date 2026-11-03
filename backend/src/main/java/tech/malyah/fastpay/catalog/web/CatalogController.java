package tech.malyah.fastpay.catalog.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tech.malyah.fastpay.catalog.Catalog;
import tech.malyah.fastpay.catalog.Catalog.Done;
import tech.malyah.fastpay.catalog.Catalog.ImageRefused;
import tech.malyah.fastpay.catalog.Catalog.Invalid;
import tech.malyah.fastpay.catalog.Catalog.Outcome;
import tech.malyah.fastpay.catalog.Catalog.ProductInput;
import tech.malyah.fastpay.catalog.Catalog.Refused;
import tech.malyah.fastpay.files.ImageRejection;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Catálogo da unidade escolhida na sessão (H13). Consulta: qualquer vínculo ativo (preço só para quem opera comanda).
 * Produtos, setores e fotos: administrador do estabelecimento. Disponibilidade: quem tem
 * {@code ALTERAR_DISPONIBILIDADE}. A permissão é conferida no servidor a cada chamada (403 {@code PERMISSAO_NEGADA}).
 * Alterar ou arquivar pede a versão lida; outra versão responde 409 {@code VERSAO_DESATUALIZADA}.
 */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

    private final Catalog catalog;
    private final SessionContext session;

    CatalogController(Catalog catalog, SessionContext session) {
        this.catalog = catalog;
        this.session = session;
    }

    @GetMapping
    Catalog.Listing listing(@RequestParam(defaultValue = "0") int page, HttpServletRequest request) {
        return catalog.listing(session.requireIdentity(request), session.requireSelectedUnit(request), page);
    }

    @PostMapping("/sectors")
    Catalog.Sector createSector(@RequestBody SectorRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.createSector(actor, session.requireSelectedUnit(request), body.name(), correlationId()));
    }

    @PutMapping("/sectors/{id}")
    Catalog.Sector renameSector(@PathVariable UUID id, @RequestBody SectorRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.renameSector(actor, session.requireSelectedUnit(request), id, version(body.version()),
                body.name(), correlationId()));
    }

    @PostMapping("/sectors/{id}/archive")
    Catalog.Sector archiveSector(@PathVariable UUID id, @RequestBody VersionRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.archiveSector(actor, session.requireSelectedUnit(request), id, version(body.version()),
                correlationId()));
    }

    @PostMapping("/products")
    Catalog.Product createProduct(@RequestBody ProductRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.createProduct(actor, session.requireSelectedUnit(request), body.input(),
                correlationId()));
    }

    @PutMapping("/products/{id}")
    Catalog.Product updateProduct(@PathVariable UUID id, @RequestBody ProductRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.updateProduct(actor, session.requireSelectedUnit(request), id, version(body.version()),
                body.input(), correlationId()));
    }

    @PostMapping("/products/{id}/archive")
    Catalog.Product archiveProduct(@PathVariable UUID id, @RequestBody VersionRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.archiveProduct(actor, session.requireSelectedUnit(request), id, version(body.version()),
                correlationId()));
    }

    @PutMapping("/products/{id}/availability")
    Catalog.Product availability(@PathVariable UUID id, @RequestBody AvailabilityRequest body,
            HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        if (body.available() == null) {
            throw error(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Informe se o produto está disponível.");
        }
        return respond(catalog.setAvailability(actor, session.requireSelectedUnit(request), id, body.available(),
                correlationId()));
    }

    @PutMapping(path = "/products/{id}/photo", consumes = "multipart/form-data")
    Catalog.Product photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        return respond(catalog.setPhoto(actor, unit, id, bytes(file), correlationId()));
    }

    @DeleteMapping("/products/{id}/photo")
    Catalog.Product removePhoto(@PathVariable UUID id, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(catalog.removePhoto(actor, session.requireSelectedUnit(request), id, correlationId()));
    }

    private static <T> T respond(Outcome<T> outcome) {
        return switch (outcome) {
            case Done<T> done -> done.value();
            case Invalid<T> invalid -> {
                ErrorResponseException e = error(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", invalid.message());
                e.getBody().setProperty("field", invalid.field());
                throw e;
            }
            case ImageRefused<T> image -> throw error(image.reason() == ImageRejection.ARQUIVO_GRANDE
                    ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.UNPROCESSABLE_CONTENT, image.reason().name(),
                    image.message());
            case Refused<T> refused -> throw refusal(refused.refusal());
        };
    }

    private static ErrorResponseException refusal(Catalog.Refusal refusal) {
        return switch (refusal) {
            case NAO_ENCONTRADO -> error(HttpStatus.NOT_FOUND, refusal.name(),
                    "Não encontrado no catálogo desta unidade. Atualize a lista.");
            case VERSAO_DESATUALIZADA -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Outra pessoa alterou este cadastro. Atualize a lista e confira antes de salvar.");
            case NOME_EM_USO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Já existe outro cadastro com este nome. Use um nome diferente (ex.: inclua o tamanho).");
            case SETOR_INVALIDO -> error(HttpStatus.UNPROCESSABLE_CONTENT, refusal.name(),
                    "Escolha um setor ativo desta unidade ou marque que o produto não vai para produção.");
            case SETOR_EM_USO -> error(HttpStatus.CONFLICT, refusal.name(),
                    "Há produtos ativos neste setor. Mude o setor deles ou arquive-os antes.");
        };
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static long version(Long version) {
        if (version == null) {
            throw error(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Informe a versão lida do cadastro.");
        }
        return version;
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    record SectorRequest(String name, Long version) {
    }

    record VersionRequest(Long version) {
    }

    record AvailabilityRequest(Boolean available) {
    }

    /** Preço como texto ("12,50" ou "12.50"); número JSON também é aceito e convertido sem arredondar. */
    record ProductRequest(String name, String category, String description, Object price, UUID sectorId,
            Boolean available, Long version) {
        ProductInput input() {
            String p = switch (price) {
                case null -> null;
                case String s -> s;
                case BigDecimal d -> d.toPlainString();
                case Number n -> new BigDecimal(n.toString()).toPlainString();
                default -> null;
            };
            return new ProductInput(name, category, description, p, sectorId, available == null || available);
        }
    }
}
