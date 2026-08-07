package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @TempDir
    Path directory;

    private final Logger logger = Logger.getAnonymousLogger();
    private final MutableClock clock = new MutableClock(NOW);
    private final Server server = mock(Server.class);
    private final Plugin plugin = mock(Plugin.class);
    private ProfileRepository profiles;

    @BeforeEach
    void openProfiles() {
        profiles = new ProfileRepository(new ProfileStore(directory, logger), logger);
        // Delivery completes on the main thread; run it inline so join() observes the result.
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
    }

    @AfterEach
    void closeProfiles() {
        profiles.close();
    }

    @Test
    void normalizeCollapsesWhitespaceAndRemovesControlCharacters() {
        assertEquals("hello there", MailService.normalize("  hello \t there  "));
        assertEquals("one two", MailService.normalize("one\n\r\ntwo"));
        assertEquals("a b", MailService.normalize("ab"));
        assertEquals("", MailService.normalize(null));
        assertEquals("", MailService.normalize("   "));
    }

    @Test
    void mailCannotBeSentToYourself() {
        MailService mail = mail(defaults());
        UUID id = UUID.randomUUID();
        Player sender = sender(id);

        assertEquals(
                MailService.SendResult.SELF,
                mail.sendAsync(sender, offline(id, true), "Hello").join()
        );
    }

    @Test
    void emptyAndOverlongMessagesAreRejected() {
        MailService mail = mail(settings(config -> config.set("mail.maximum-length", 16)));
        Player sender = sender(UUID.randomUUID());
        OfflinePlayer recipient = offline(UUID.randomUUID(), true);

        assertEquals(MailService.SendResult.EMPTY, mail.sendAsync(sender, recipient, "   ").join());
        assertEquals(
                MailService.SendResult.TOO_LONG,
                mail.sendAsync(sender, recipient, "x".repeat(17)).join()
        );
        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "x".repeat(16)).join());
    }

    @Test
    void lengthIsCountedInCodePointsSoEmojiAreNotPenalisedTwice() {
        MailService mail = mail(settings(config -> config.set("mail.maximum-length", 16)));
        Player sender = sender(UUID.randomUUID());

        // Sixteen emoji would be 32 chars but is exactly sixteen code points.
        assertEquals(
                MailService.SendResult.SENT,
                mail.sendAsync(sender, offline(UUID.randomUUID(), true), "😀".repeat(16)).join()
        );
    }

    @Test
    void unknownRecipientsAreRejected() {
        MailService mail = mail(defaults());
        Player sender = sender(UUID.randomUUID());

        assertEquals(MailService.SendResult.UNKNOWN_PLAYER, mail.sendAsync(sender, null, "Hello").join());
        assertEquals(
                MailService.SendResult.UNKNOWN_PLAYER,
                mail.sendAsync(sender, offline(UUID.randomUUID(), false), "Hello").join()
        );
    }

    @Test
    void disablingMailOnTheServerRejectsEverySend() {
        MailService mail = mail(settings(config -> config.set("mail.enabled", false)));

        assertEquals(
                MailService.SendResult.DISABLED,
                mail.sendAsync(sender(UUID.randomUUID()), offline(UUID.randomUUID(), true), "Hello").join()
        );
    }

    @Test
    void recipientsWhoDisabledMailOrBlockedTheSenderAreUnavailable() {
        MailService mail = mail(defaults());
        UUID senderId = UUID.randomUUID();
        Player sender = sender(senderId);

        UUID mailOff = UUID.randomUUID();
        Profile profile = profiles.load(mailOff);
        profile.preferences(profile.preferences().withMail(false));
        profiles.save(profile);
        assertEquals(
                MailService.SendResult.UNAVAILABLE,
                mail.sendAsync(sender, offline(mailOff, true), "Hello").join()
        );

        UUID blocking = UUID.randomUUID();
        Profile blocker = profiles.load(blocking);
        blocker.blockMailFrom(senderId);
        profiles.save(blocker);
        assertEquals(
                MailService.SendResult.UNAVAILABLE,
                mail.sendAsync(sender, offline(blocking, true), "Hello").join()
        );
    }

    @Test
    void theSendCooldownBlocksRepeatsUntilItExpires() {
        MailService mail = mail(settings(config -> config.set("mail.cooldown-seconds", 30)));
        Player sender = sender(UUID.randomUUID());
        OfflinePlayer recipient = offline(UUID.randomUUID(), true);

        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "One").join());
        assertEquals(MailService.SendResult.COOLDOWN, mail.sendAsync(sender, recipient, "Two").join());

        clock.advanceSeconds(29);
        assertEquals(MailService.SendResult.COOLDOWN, mail.sendAsync(sender, recipient, "Three").join());

        clock.advanceSeconds(1);
        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "Four").join());
    }

    @Test
    void theHourlyLimitForgetsSendsOlderThanAnHour() {
        MailService mail = mail(settings(config -> {
            config.set("mail.cooldown-seconds", 0);
            config.set("mail.maximum-per-hour", 2);
        }));
        Player sender = sender(UUID.randomUUID());
        OfflinePlayer recipient = offline(UUID.randomUUID(), true);

        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "One").join());
        clock.advanceSeconds(10);
        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "Two").join());
        assertEquals(MailService.SendResult.HOURLY_LIMIT, mail.sendAsync(sender, recipient, "Three").join());

        // The first send falls out of the window, freeing exactly one slot.
        clock.advanceSeconds(3591);
        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender, recipient, "Four").join());
        assertEquals(MailService.SendResult.HOURLY_LIMIT, mail.sendAsync(sender, recipient, "Five").join());
    }

    @Test
    void limitsAreTrackedPerSenderRatherThanGlobally() {
        MailService mail = mail(settings(config -> config.set("mail.cooldown-seconds", 30)));
        OfflinePlayer recipient = offline(UUID.randomUUID(), true);

        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender(UUID.randomUUID()), recipient, "One").join());
        assertEquals(MailService.SendResult.SENT, mail.sendAsync(sender(UUID.randomUUID()), recipient, "Two").join());
    }

    @Test
    void deliveredMailKeepsTheSenderUuidSoRepliesSurviveNameChanges() {
        MailService mail = mail(defaults());
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        assertEquals(
                MailService.SendResult.SENT,
                mail.sendAsync(sender(senderId), offline(recipientId, true), "  Hello   there  ").join()
        );

        Profile inbox = profiles.load(recipientId);
        assertEquals(1L, inbox.unreadMailCount());
        assertEquals(senderId, inbox.notifications().getFirst().actorId());
        assertEquals("Hello there", inbox.notifications().getFirst().detail());
    }

    @Test
    void blockingIsIdempotentAndCannotTargetYourself() {
        MailService mail = mail(defaults());
        UUID ownerId = UUID.randomUUID();
        Player owner = sender(ownerId);
        OfflinePlayer other = offline(UUID.randomUUID(), true);

        assertTrue(mail.block(owner, other));
        assertFalse(mail.block(owner, other));
        assertTrue(mail.unblock(owner, other));
        assertFalse(mail.unblock(owner, other));

        assertFalse(mail.block(owner, offline(ownerId, true)));
        assertFalse(mail.block(owner, null));
        assertFalse(mail.unblock(owner, null));
    }

    @Test
    void blocksSurviveAReloadOfTheProfile() {
        MailService mail = mail(defaults());
        UUID ownerId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();

        assertTrue(mail.block(sender(ownerId), offline(blockedId, true)));
        profiles.close();

        profiles = new ProfileRepository(new ProfileStore(directory, logger), logger);
        assertTrue(profiles.load(ownerId).blocksMailFrom(blockedId));
    }

    @Test
    void findRecipientPrefersOnlinePlayersAndIgnoresStrangers() {
        MailService mail = mail(defaults());
        Player online = sender(UUID.randomUUID());
        when(server.getPlayerExact("Alex")).thenReturn(online);
        OfflinePlayer seen = offline(UUID.randomUUID(), true);
        when(server.getOfflinePlayerIfCached("Sam")).thenReturn(seen);
        OfflinePlayer stranger = offline(UUID.randomUUID(), false);
        when(server.getOfflinePlayerIfCached("Nobody")).thenReturn(stranger);

        assertEquals(online, mail.findRecipient("Alex"));
        assertEquals(seen, mail.findRecipient("Sam"));
        assertNull(mail.findRecipient("Nobody"));
        assertNull(mail.findRecipient("  "));
        assertNull(mail.findRecipient(null));
    }

    private MailService mail(SettingsService settings) {
        return new MailService(
                plugin,
                server,
                settings,
                profiles,
                new NotificationService(profiles, clock),
                new MessageService(Map.of(), Map.of(), logger),
                new FeedbackService(new YamlConfiguration(), logger),
                clock
        );
    }

    private SettingsService defaults() {
        return settings(config -> {
        });
    }

    private SettingsService settings(java.util.function.Consumer<YamlConfiguration> overrides) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8
        ));
        overrides.accept(config);
        return new SettingsService(PluginSettings.validate(config));
    }

    private Player sender(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Player-" + id.toString().substring(0, 8));
        when(player.locale()).thenReturn(Locale.US);
        return player;
    }

    private OfflinePlayer offline(UUID id, boolean known) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPlayedBefore()).thenReturn(known);
        return player;
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
