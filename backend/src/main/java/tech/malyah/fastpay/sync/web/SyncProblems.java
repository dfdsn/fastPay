package tech.malyah.fastpay.sync.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tech.malyah.fastpay.sync.TooManyStreamsException;

/** Recusa de conexão em tempo real (equipe e consumidor): 429 {@code MUITAS_CONEXOES}; o cliente segue por polling. */
@RestControllerAdvice
class SyncProblems {

    @ExceptionHandler(TooManyStreamsException.class)
    ProblemDetail tooManyStreams() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Há conexões em tempo real demais abertas neste aparelho. A tela continua se atualizando, um pouco mais devagar.");
        problem.setType(URI.create("urn:fastpay:problema:muitas_conexoes"));
        problem.setProperty("code", "MUITAS_CONEXOES");
        return problem;
    }
}
