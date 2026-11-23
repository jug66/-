package dev.messageboard.comment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommentRevision(UUID id, UUID commentId, UUID editorId, int revision,
                              String content, OffsetDateTime createdAt) {}
