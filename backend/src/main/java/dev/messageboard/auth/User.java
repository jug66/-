package dev.messageboard.auth;

import java.util.UUID;
import java.time.OffsetDateTime;

public record User(UUID id, String loginName, String displayName, String passwordHash,
                   String email, OffsetDateTime emailVerifiedAt) {
    public View view() { return new View(id, loginName, displayName, email, emailVerifiedAt != null); }
    public record View(UUID id, String loginName, String displayName,
                       String email, boolean emailVerified) implements java.io.Serializable {}
}
