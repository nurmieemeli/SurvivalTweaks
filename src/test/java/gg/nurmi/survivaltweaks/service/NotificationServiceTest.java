package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    @TempDir
    Path directory;

    @Test
    void loadsAnOfflineRecipientAsynchronouslyAndUpdatesOnTheProvidedExecutor() throws Exception {
        ProfileRepository profiles = mock(ProfileRepository.class);
        Profile recipient = new Profile(UUID.randomUUID());
        CompletableFuture<Profile> loaded = new CompletableFuture<>();
        when(profiles.loadAsync(recipient.uniqueId())).thenReturn(loaded);
        var mainThread = new LinkedBlockingQueue<Runnable>();
        NotificationService notifications = new NotificationService(profiles, Clock.systemUTC());

        var notified = notifications.notifyAsync(recipient.uniqueId(), NotificationType.LOCK_ACCESS_DENIED,
                "Alex", "Chest", mainThread::add);
        assertFalse(notified.isDone());
        loaded.complete(recipient);
        Runnable update = mainThread.poll(1, TimeUnit.SECONDS);
        assertNotNull(update);
        assertTrue(recipient.notifications().isEmpty());
        update.run();

        assertTrue(notified.get(1, TimeUnit.SECONDS));
        assertEquals(1, recipient.notifications().size());
        verify(profiles).save(recipient);
        verify(profiles, never()).load(recipient.uniqueId());
    }

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
