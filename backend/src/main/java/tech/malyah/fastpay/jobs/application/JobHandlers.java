package tech.malyah.fastpay.jobs.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.jobs.JobHandler;

/** Executores registrados, por tipo. Dois executores para o mesmo tipo impedem a subida. */
@Component
public class JobHandlers {

    private final Map<String, JobHandler> byKind;

    JobHandlers(List<JobHandler> handlers) {
        this.byKind = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::kind, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Mais de um executor para o tipo de job " + a.kind());
                }));
    }

    public Optional<JobHandler> find(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }
}
