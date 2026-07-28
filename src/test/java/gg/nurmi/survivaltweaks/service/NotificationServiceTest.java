package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {

    @TempDir
    Path directory;

    @Test
    void suppressesRecentUnreadDuplicatesAndPersistsTheInbox() {
        UUID playerId = UUID.randomUUID();
        Logger logger = Logger.getLogger(NotificationServiceTest.class.getName());
        Instant now = Instant.parse("2026-07-27T12:00:00Z");

        try (ProfileRepository profiles = new ProfileRepository(
                new ProfileStore(directory, logger),
                logger
        )) {
            NotificationService notifications = new NotificationService(
                    profiles,
                    Clock.fixed(now, ZoneOffset.UTC)
            );

            assertTrue(notifications.notify(
                    playerId,
                    NotificationType.TELEPORT_DECLINED,
                    "Alex",
                    ""
            ));
            assertFalse(notifications.notify(
                    playerId,
                    NotificationType.TELEPORT_DECLINED,
                    "Alex",
                    ""
            ));
            assertEquals(1L, notifications.unread(playerId));
        }

        try (ProfileRepository profiles = new ProfileRepository(
                new ProfileStore(directory, logger),
                logger
        )) {
            assertEquals(1L, profiles.load(playerId).unreadNotificationCount());
        }
    }

    @Test
    void mailPreservesSenderIdentityAndDoesNotCollapseRepeatedLetters() {
        UUID playerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        Logger logger = Logger.getLogger(NotificationServiceTest.class.getName());
        try (ProfileRepository profiles = new ProfileRepository(
                new ProfileStore(directory, logger),
                logger
        )) {
            NotificationService notifications = new NotificationService(
                    profiles,
                    Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)
            );

            assertTrue(notifications.mail(playerId, senderId, "Alex", "Hello"));
            assertTrue(notifications.mail(playerId, senderId, "Alex", "Hello again"));
            assertEquals(2L, profiles.load(playerId).unreadMailCount());
            assertEquals(senderId, profiles.load(playerId).notifications().getFirst().actorId());
        }
    }
}
