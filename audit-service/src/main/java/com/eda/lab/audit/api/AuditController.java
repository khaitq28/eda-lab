package com.eda.lab.audit.api;

import com.eda.lab.audit.api.dto.AuditLogResponse;
import com.eda.lab.audit.api.dto.DocumentTimelineResponse;
import com.eda.lab.audit.domain.entity.AuditLog;
import com.eda.lab.audit.domain.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for querying audit logs.
 * 
 * Endpoints:
 * - GET /audit?documentId={uuid} - Get all events for a document
 * - GET /audit/events/{eventId} - Get specific event by eventId
 * - GET /audit/timeline/{documentId} - Get event timeline for a document
 * - GET /audit/stats - Get audit statistics
 * 
 * Use Cases:
 * - Debugging: "What happened to document X?"
 * - Observability: "What's the full event flow?"
 * - Troubleshooting: "Did event Y get processed?"
 * - Compliance: "Show me the audit trail"
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    /**
     * Get all audit logs for a document.
     * 
     * GET /api/v1/audit?documentId={uuid}
     * 
     * @param documentId Document ID
     * @return List of audit logs
     */
    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAuditLogsByDocument(
            @RequestParam("documentId") UUID documentId) {
        
        log.info("GET /api/v1/audit?documentId={}", documentId);
        
        // Sort by received_at ASC to show event flow chronologically
        List<AuditLog> auditLogs = auditLogRepository.findByAggregateIdOrderByReceivedAtAsc(documentId);
        List<AuditLogResponse> response = auditLogs.stream()
                .map(AuditLogResponse::from)
                .collect(Collectors.toList());
        
        log.info("Found {} audit logs for document {} (sorted chronologically)", response.size(), documentId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get specific audit log by event ID.
     * 
     * GET /api/v1/audit/events/{eventId}
     * 
     * @param eventId Event ID
     * @return Audit log
     */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<AuditLogResponse> getAuditLogByEventId(@PathVariable UUID eventId) {
        log.info("GET /audit/events/{}", eventId);
        
        return auditLogRepository.findByEventId(eventId)
                .map(AuditLogResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Audit log not found for eventId: {}", eventId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Get event timeline for a document.
     * Shows the sequence of events in chronological order.
     * 
     * GET /api/v1/audit/timeline/{documentId}
     * 
     * Example response:
     * {
     *   "documentId": "...",
     *   "eventTimeline": ["DocumentUploaded", "DocumentValidated", "DocumentEnriched"],
     *   "eventCount": 3
     * }
     * 
     * @param documentId Document ID
     * @return Event timeline
     */
    @GetMapping("/timeline/{documentId}")
    public ResponseEntity<DocumentTimelineResponse> getDocumentTimeline(@PathVariable UUID documentId) {
        log.info("GET /audit/timeline/{}", documentId);
        
        List<String> timeline = auditLogRepository.findEventTimelineByAggregateId(documentId);
        long count = auditLogRepository.countByAggregateId(documentId);
        
        DocumentTimelineResponse response = new DocumentTimelineResponse(documentId, timeline, count);
        
        log.info("Timeline for document {}: {}", documentId, timeline);
        return ResponseEntity.ok(response);
    }

    /**
     * Get audit statistics.
     * 
     * GET /api/v1/audit/stats
     * 
     * @return Statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getAuditStats() {
        log.info("GET /audit/stats");
        
        long totalEvents = auditLogRepository.count();
        long uploadedCount = auditLogRepository.countByEventType("DocumentUploaded");
        long validatedCount = auditLogRepository.countByEventType("DocumentValidated");
        long rejectedCount = auditLogRepository.countByEventType("DocumentRejected");
        long enrichedCount = auditLogRepository.countByEventType("DocumentEnriched");
        
        var stats = new java.util.HashMap<String, Object>();
        stats.put("totalEvents", totalEvents);
        stats.put("byEventType", new java.util.HashMap<String, Long>() {{
            put("DocumentUploaded", uploadedCount);
            put("DocumentValidated", validatedCount);
            put("DocumentRejected", rejectedCount);
            put("DocumentEnriched", enrichedCount);
        }});
        
        log.info("Audit stats: total={}, uploaded={}, validated={}, rejected={}, enriched={}", 
                totalEvents, uploadedCount, validatedCount, rejectedCount, enrichedCount);
        
        return ResponseEntity.ok(stats);
    }

    /**
     * Get all audit logs of a specific event type.
     * 
     * GET /api/v1/audit/events/type/{eventType}
     * 
     * @param eventType Event type
     * @return List of audit logs
     */
    @GetMapping("/events/type/{eventType}")
    public ResponseEntity<List<AuditLogResponse>> getAuditLogsByEventType(@PathVariable String eventType) {
        log.info("GET /audit/events/type/{}", eventType);
        
        List<AuditLog> auditLogs = auditLogRepository.findByEventTypeOrderByReceivedAtDesc(eventType);
        List<AuditLogResponse> response = auditLogs.stream()
                .map(AuditLogResponse::from)
                .collect(Collectors.toList());
        
        log.info("Found {} audit logs for event type {}", response.size(), eventType);
        return ResponseEntity.ok(response);
    }
}
