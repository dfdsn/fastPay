package tech.malyah.fastpay.jobs;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Intenção de efeito assíncrono. {@code dedupKey} identifica o efeito dentro da unidade e do tipo:
 * pedir de novo o mesmo efeito (evento repetido, reenvio do comando) não cria outro job. Usar algo
 * estável como o ID do fato de origem, nunca um valor aleatório gerado no envio.
 *
 * <p>{@code payload} é JSON com o mínimo para executar; sem segredos, tokens ou dados de cartão.
 */
public record JobRequest(String kind, String dedupKey, String payload, String correlationId) {

    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9_.]{2,79}$");

    public JobRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dedupKey, "dedupKey");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("Tipo de job inválido: " + kind);
        }
        if (dedupKey.isBlank() || dedupKey.length() > 200) {
            throw new IllegalArgumentException("Chave de deduplicação deve ter de 1 a 200 caracteres");
        }
        if (correlationId != null && correlationId.length() > 100) {
            throw new IllegalArgumentException("correlationId deve ter até 100 caracteres");
        }
        payload = payload == null ? "{}" : payload;
    }

    public JobRequest(String kind, String dedupKey) {
        this(kind, dedupKey, null, null);
    }
}
