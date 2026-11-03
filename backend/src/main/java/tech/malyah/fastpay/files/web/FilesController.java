package tech.malyah.fastpay.files.web;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.files.Images;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Download de imagem da unidade escolhida na sessão (H13). Exige vínculo ativo na unidade; arquivo de outra unidade,
 * removido ou inexistente responde 404 igual. O conteúdo é sempre a imagem regravada pelo servidor, com o tipo
 * guardado, sem farejamento pelo navegador e sem permissão para executar nada.
 */
@RestController
@RequestMapping("/api/v1/files")
class FilesController {

    private final Images images;
    private final SessionContext session;

    FilesController(Images images, SessionContext session) {
        this.images = images;
        this.session = session;
    }

    @GetMapping("/{id}")
    ResponseEntity<?> download(@PathVariable UUID id, HttpServletRequest request) {
        UUID identity = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        return images.read(identity, unit, id)
                .<ResponseEntity<?>>map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(content.contentType()))
                        .contentLength(content.bytes().length)
                        // O conteúdo de um id nunca muda; cache só no navegador da pessoa.
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                        .eTag('"' + content.sha256() + '"')
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(content.bytes()))
                .orElseGet(() -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                            "Imagem não encontrada nesta unidade.");
                    problem.setType(java.net.URI.create("urn:fastpay:problema:arquivo_nao_encontrado"));
                    problem.setProperty("code", "ARQUIVO_NAO_ENCONTRADO");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(problem);
                });
    }
}
