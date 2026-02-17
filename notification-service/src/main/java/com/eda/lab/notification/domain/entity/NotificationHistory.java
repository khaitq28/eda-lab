package com.eda.lab.notification.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for notification history.
 * 
 * Purpose:
 * - Store history of all notifications sent
 * - Enable audit trail and debugging
 * - Track notification success/failure
 * 
 * Use Cases:
 * - "Did we send a notification for document X?"
 * - "What notifications failed?"
 * - "What did we tell the user?"
 */
@Entity
@Table(name = "notification_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(name = "notification_type", nullable = false)
    private String notificationType; // EMAIL, SMS, PUSH

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Column(name = "subject")
    private String subject;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "status", nullable = false)
    private String status; // SENT, FAILED

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Constructor for successful notification.
     */
    public NotificationHistory(UUID eventId, String eventType, UUID aggregateId, String routingKey,
                                String notificationType, String recipient, String subject, String message) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.routingKey = routingKey;
        this.notificationType = notificationType;
        this.recipient = recipient;
        this.subject = subject;
        this.message = message;
        this.status = "SENT";
        this.sentAt = Instant.now();
    }
}
