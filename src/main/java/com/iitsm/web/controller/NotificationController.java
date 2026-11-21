package com.iitsm.web.controller;

import com.iitsm.application.service.NotificationService;
import com.iitsm.web.dto.NotificationDto;
import com.iitsm.web.security.CurrentUser;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// OOP (Single responsibility): NotificationController validates input, calls one application service and maps the result to a DTO; no business rule lives here.
/**
 * Controller exposing real-time SSE notification stream and persisted in-app notification queries.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    // OOP (Dependency Injection): services arrive through the constructor, so the controller depends on classes it is given, not ones it creates.
    public NotificationController(
            NotificationService notificationService,
            CurrentUser currentUser) {
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
    }

    private String resolveRecipientId(Authentication authentication) {
        if (authentication == null) {
            return "ANONYMOUS";
        }
        return currentUser.idOf(authentication);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(Authentication authentication) {
        String recipientId = resolveRecipientId(authentication);
        return notificationService.subscribe(recipientId);
    }

    @GetMapping
    public ResponseEntity<List<NotificationDto>> getMyNotifications(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        String recipientId = resolveRecipientId(authentication);
        List<NotificationDto> notifications = notificationService.getNotificationsForUser(recipientId).stream()
                .map(NotificationDto::fromDomain)
                .collect(Collectors.toList());
        return ResponseEntity.ok(notifications);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable("id") String id, Authentication authentication) {
        notificationService.markDelivered(id, resolveRecipientId(authentication));
        return ResponseEntity.noContent().build();
    }
}
