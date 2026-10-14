package tech.malyah.fastpay.establishments.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Registro de unidade (tabela global {@code establishment}), mapeado só para a validação do Hibernate. O
 * cadastro pelo admin fastPay (H10) grava por {@link JdbcEstablishmentStore}; o runtime só lê o que o
 * contexto de RLS libera.
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
