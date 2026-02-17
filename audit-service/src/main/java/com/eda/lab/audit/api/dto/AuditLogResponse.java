package com.eda.lab.audit.api.dto;

import com.eda.lab.audit.domain.entity.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for audit log response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {
    private UUID id;
    private UUID eventId;
    private String eventType;
    private UUID aggregateId;
    private String aggregateType;
    private String routingKey;
    private String payloadJson;
    private Instant receivedAt;
    private String messageId;
    private String correlationId;

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getEventId(),
                auditLog.getEventType(),
                auditLog.getAggregateId(),
                auditLog.getAggregateType(),
                auditLog.getRoutingKey(),
                auditLog.getPayloadJson(),
                auditLog.getReceivedAt(),
                auditLog.getMessageId(),
                auditLog.getCorrelationId()
        );
    }
}
