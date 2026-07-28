package gg.nurmi.survivaltweaks.object;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerNotification(
        UUID id,
        NotificationType type,
        Instant createdAt,
        UUID actorId,
        String actor,
        String detail,
        boolean read
) {

    public PlayerNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        actor = actor == null ? "" : actor.strip();
        detail = detail == null ? "" : detail.strip();
    }

    public static PlayerNotification create(
            NotificationType type,
            Instant createdAt,
            String actor,
            String detail
    ) {
        return create(type, createdAt, null, actor, detail);
    }

    public static PlayerNotification create(
            NotificationType type,
            Instant createdAt,
            UUID actorId,
            String actor,
            String detail
    ) {
        return new PlayerNotification(
                UUID.randomUUID(),
                type,
                createdAt,
                actorId,
                actor,
                detail,
                false
        );
    }

    public PlayerNotification withRead(boolean updatedRead) {
        return new PlayerNotification(id, type, createdAt, actorId, actor, detail, updatedRead);
    }
}
