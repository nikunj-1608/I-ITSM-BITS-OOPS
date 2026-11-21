package com.iitsm.web.dto;

import com.iitsm.domain.model.Notification;
import java.time.Instant;

// OOP (Immutability): NotificationDto is a record, so it is a read-only data carrier with no setters.
/**
 * DTO representing an in-app user notification.
 */
public record NotificationDto(
        String id,
        String recipientId,
        String channel,
        String subject,
        String message,
        boolean delivered,
        Instant createdAt
) {
    public static NotificationDto fromDomain(Notification n) {
        if (n == null) return null;
        return new NotificationDto(
                n.getId(),
                n.getRecipientId(),
                n.getChannel(),
                n.getSubject(),
                n.getMessage(),
                n.isDelivered(),
                n.getCreatedAt()
        );
    }
}

