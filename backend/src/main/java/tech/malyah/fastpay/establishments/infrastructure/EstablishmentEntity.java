package tech.malyah.fastpay.establishments.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Registro de unidade (tabela global {@code establishment}). Escrito apenas pelas migrations e,
 * a partir de H10, pelo fluxo administrativo; o runtime só lê a unidade do contexto atual (RLS).
 */
@Entity
@Table(name = "establishment")
public class EstablishmentEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected EstablishmentEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
