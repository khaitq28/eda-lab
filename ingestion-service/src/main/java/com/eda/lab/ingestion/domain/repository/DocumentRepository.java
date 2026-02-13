package com.eda.lab.ingestion.domain.repository;

import com.eda.lab.ingestion.domain.entity.Document;
import com.eda.lab.ingestion.domain.entity.Document.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Document entity.
 * Spring Data JPA provides basic CRUD operations.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * Find documents by status.
     * Useful for monitoring and batch processing.
     */
    List<Document> findByStatus(DocumentStatus status);

    /**
     * Find documents created after a specific time.
     * Useful for incremental processing.
     */
    List<Document> findByCreatedAtAfter(Instant after);

    /**
     * Count documents by status.
     * Useful for metrics and monitoring.
     */
    long countByStatus(DocumentStatus status);

    /**
     * Check if document with name exists.
     * Can be used for duplicate detection if needed.
     */
    boolean existsByName(String name);

    /**
     * Custom query example: Find recent documents.
     * Demonstrates JPQL usage.
     */
    @Query("SELECT d FROM Document d WHERE d.createdAt >= :since ORDER BY d.createdAt DESC")
    List<Document> findRecentDocuments(Instant since);
}
