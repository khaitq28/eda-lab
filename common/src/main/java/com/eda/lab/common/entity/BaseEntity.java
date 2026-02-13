package com.eda.lab.common.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.UUID;

/**
 * Base entity with common audit fields for all JPA entities.
 * Uses Lombok for familiar Spring Boot style.
 * 
 * Services should extend this for their domain entities.
 * 
 * Note: JPA entities are NOT immutable by design, so we use Lombok @Data
 * instead of Records. This is appropriate for database-backed entities.
 */
@Data
@EqualsAndHashCode(of = "id")
public abstract class BaseEntity {

    private UUID id;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    /**
     * Called before entity is persisted.
     * JPA @PrePersist equivalent (services should add the annotation).
     */
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * Called before entity is updated.
     * JPA @PreUpdate equivalent (services should add the annotation).
     */
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
