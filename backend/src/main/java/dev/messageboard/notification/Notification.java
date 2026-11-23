package dev.messageboard.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Notification(UUID id, UUID recipientId, UUID actorId, String actorName,
                           UUID commentId, String type, String preview,
                           OffsetDateTime createdAt, OffsetDateTime readAt) {}
