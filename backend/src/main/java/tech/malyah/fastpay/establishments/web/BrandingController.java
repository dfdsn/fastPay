package tech.malyah.fastpay.establishments.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tech.malyah.fastpay.establishments.application.Branding;
import tech.malyah.fastpay.files.ImageRejection;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Nome e logo da unidade escolhida na sessão (H13, FR-67). Ver: qualquer vínculo ativo. Incluir, substituir e remover
 * o logo: administrador do estabelecimento (403 {@code PERMISSAO_NEGADA} para os demais).
 */
@RestController
@RequestMapping("/api/v1/establishment")
class BrandingController {

    private final Branding branding;
    private final SessionContext session;

    BrandingController(Branding branding, SessionContext session) {
        this.branding = branding;
        this.session = session;
    }

    @GetMapping
    Branding.View view(HttpServletRequest request) {
        return branding.view(session.requireIdentity(request), session.requireSelectedUnit(request));
    }

    @PutMapping(path = "/logo", consumes = "multipart/form-data")
    Branding.View setLogo(@RequestPart("file") MultipartFile file, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return respond(branding.setLogo(actor, unit, bytes, UUID.randomUUID().toString()));
    }

    @DeleteMapping("/logo")
    Branding.View removeLogo(HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        return respond(branding.removeLogo(actor, session.requireSelectedUnit(request), UUID.randomUUID().toString()));
    }

    private static Branding.View respond(Branding.Outcome outcome) {
        return switch (outcome) {
            case Branding.Done done -> done.view();
            case Branding.ImageRefused refused -> {
                HttpStatus status = refused.reason() == ImageRejection.ARQUIVO_GRANDE
                        ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.UNPROCESSABLE_CONTENT;
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, refused.message());
                problem.setType(URI.create("urn:fastpay:problema:" + refused.reason().name().toLowerCase()));
                problem.setProperty("code", refused.reason().name());
                throw new ErrorResponseException(status, problem, null);
            }
        };
    }
}
