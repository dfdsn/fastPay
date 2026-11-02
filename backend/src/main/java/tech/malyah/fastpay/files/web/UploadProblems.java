package tech.malyah.fastpay.files.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import tech.malyah.fastpay.files.ImageRejection;
import tech.malyah.fastpay.files.Images;

/**
 * Envio de arquivo recusado antes de chegar ao controller (H13): corpo acima do limite do servidor ou envio sem o
 * campo do arquivo. Mesma resposta e mensagem da verificação da imagem.
 */
@RestControllerAdvice
class UploadProblems {

    private final Images images;

    UploadProblems(Images images) {
        this.images = images;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ImageRejection.ARQUIVO_GRANDE.name(),
                images.message(ImageRejection.ARQUIVO_GRANDE));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ProblemDetail missing() {
        return problem(HttpStatus.BAD_REQUEST, ImageRejection.ARQUIVO_VAZIO.name(),
                images.message(ImageRejection.ARQUIVO_VAZIO));
    }

    /** Problema no formato comum da API, para os controllers que recebem imagem. */
    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return problem;
    }
}
