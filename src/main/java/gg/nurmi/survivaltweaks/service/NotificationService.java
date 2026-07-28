package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.PlayerNotification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class NotificationService {

    private static final int MAX_NOTIFICATIONS = 80;
    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(5);

    private final ProfileRepository profiles;
    private final Clock clock;

    public NotificationService(ProfileRepository profiles, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean notify(UUID playerId, NotificationType type, String actor, String detail) {
        return notify(playerId, type, null, actor, detail, true);
    }

    public boolean mail(
            UUID playerId,
            UUID senderId,
            String senderName,
            String message
    ) {
        return notify(
                playerId,
                NotificationType.MAIL,
                senderId,
                senderName,
                message,
                false
        );
    }

    private boolean notify(
            UUID playerId,
            NotificationType type,
            UUID actorId,
            String actor,
            String detail,
            boolean deduplicate
    ) {
        var profile = profiles.load(playerId);
        Instant now = clock.instant();
        boolean duplicate = deduplicate && profile.notifications().stream().anyMatch(notification ->
                !notification.read()
                        && notification.type() == type
                        && notification.actor().equalsIgnoreCase(actor == null ? "" : actor.strip())
                        && notification.createdAt().plus(DUPLICATE_WINDOW).isAfter(now)
        );
        if (duplicate) {
            return false;
        }
        profile.addNotification(
                PlayerNotification.create(type, now, actorId, actor, detail),
                MAX_NOTIFICATIONS
        );
        profiles.save(profile);
        return true;
    }

    public long unread(UUID playerId) {
        return profiles.load(playerId).unreadNotificationCount();
    }
}
