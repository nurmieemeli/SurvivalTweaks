package gg.nurmi.survivaltweaks.object;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TeleportRequest(UUID senderId, UUID recipientId, Instant expiresAt) {

    public TeleportRequest {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (senderId.equals(recipientId)) {
            throw new IllegalArgumentException("sender and recipient must differ");
        }
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
