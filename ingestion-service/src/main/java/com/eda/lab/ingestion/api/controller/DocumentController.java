package com.eda.lab.ingestion.api.controller;

import com.eda.lab.common.dto.ApiResponse;
import com.eda.lab.ingestion.api.dto.DocumentResponse;
import com.eda.lab.ingestion.api.dto.UploadDocumentRequest;
import com.eda.lab.ingestion.domain.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for document operations.
 * Exposes endpoints for uploading and retrieving documents.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Upload a new document.
     * 
     * This endpoint:
     * 1. Validates the request
     * 2. Persists the document
     * 3. Creates an outbox event (in the same transaction)
     * 4. Returns the created document
     * 
     * The outbox event will be published to RabbitMQ by a separate process.
     * 
     * @param request Upload request with document metadata
     * @return Created document with HTTP 201 status
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(@Valid @RequestBody UploadDocumentRequest request) {
        
        log.info("Received upload request for document: {}", request.getName());
        
        DocumentResponse response = documentService.uploadDocument(request);
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document uploaded successfully", response));
    }

    /**
     * Get document by ID.
     * 
     * @param id Document ID
     * @return Document details with HTTP 200 status
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocument(@PathVariable UUID id) {
        log.debug("Received request to fetch document: {}", id);
        
        DocumentResponse response = documentService.getDocument(id);
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
