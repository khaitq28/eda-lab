package com.eda.lab.ingestion.api.dto;

import com.eda.lab.ingestion.domain.entity.Document.DocumentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for document information.
 * Returned by GET and POST endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    private UUID id;
    private String name;
    private String contentType;
    private Long fileSize;
    private DocumentStatus status;
    private Map<String, Object> metadata;
    private String correlationId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant updatedAt;

    private String createdBy;
    private String updatedBy;
}
