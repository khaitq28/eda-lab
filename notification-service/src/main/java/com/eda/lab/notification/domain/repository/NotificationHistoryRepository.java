package com.eda.lab.notification.domain.repository;

import com.eda.lab.notification.domain.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for NotificationHistory.
 * 
 * Key Methods:
 * - existsByEventId(): Check if notification already sent (idempotency)
 * - findByAggregateId(): Get all notifications for a document
 */
@Repository
public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, UUID> {

    /**
     * Check if notification already sent for this event (idempotency).
     */
    boolean existsByEventId(UUID eventId);

    /**
     * Find all notifications for a document.
     */
    List<NotificationHistory> findByAggregateIdOrderBySentAtDesc(UUID aggregateId);

    /**
     * Find notifications by recipient.
     */
    List<NotificationHistory> findByRecipientOrderBySentAtDesc(String recipient);

    /**
     * Count notifications by event type.
     */
    long countByEventType(String eventType);
}
