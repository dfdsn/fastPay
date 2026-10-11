package tech.malyah.fastpay.identity;

import java.util.UUID;

/** Dados não sensíveis da identidade, para mostrar quem está usando o sistema. */
public record IdentitySummary(UUID id, String displayName) {
}
