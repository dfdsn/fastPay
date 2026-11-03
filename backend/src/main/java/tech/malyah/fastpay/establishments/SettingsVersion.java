package tech.malyah.fastpay.establishments;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma versão imutável da configuração da unidade (H14, D-08). A comanda guarda {@link #id()} na abertura e
 * consulta sempre essa versão; salvar a configuração cria a versão {@code number + 1}, nunca altera esta.
 * {@code createdBy} nulo: padrão inicial do sistema (versão 1).
 */
public record SettingsVersion(UUID id, int number, SettingsValues values, UUID createdBy, Instant createdAt) {
}
