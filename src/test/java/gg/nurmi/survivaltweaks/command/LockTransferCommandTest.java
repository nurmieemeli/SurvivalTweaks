package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.storage.ContainerLockDataStore;
import gg.nurmi.survivaltweaks.storage.LegacyYamlFixtures.ProfileFiles;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LockTransferCommandTest {
    @TempDir
    Path folder;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void transferNotifiesRecipientAndOnlyAlertsThePreviousOwnerForAnAdmin(boolean ownerActs) throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID actorId = ownerActs ? ownerId : UUID.randomUUID();
        String actorName = ownerActs ? "Owner" : "Admin";
        Logger logger = Logger.getAnonymousLogger();
        var server = mock(Server.class);
        var actor = mock(Player.class);
        var recipient = mock(Player.class);
        when(actor.getUniqueId()).thenReturn(actorId);
        when(actor.getName()).thenReturn(actorName);
        when(actor.hasPermission(LockCommand.ADMIN_PERMISSION)).thenReturn(!ownerActs);
        when(recipient.getUniqueId()).thenReturn(recipientId);
        when(recipient.getName()).thenReturn("Recipient");
        when(server.getPlayer("Recipient")).thenReturn(recipient);
        var resolver = mock(ContainerBlockResolver.class);
        var block = new BlockKey(UUID.randomUUID(), 4, 65, 4);
        when(resolver.target(actor, 5)).thenReturn(Optional.of(
                new ContainerBlockResolver.Target(mock(Block.class), Set.of(block))));
        var settings = mock(PluginSettings.class);
        when(settings.lockTargetDistance()).thenReturn(5);
        when(settings.maxLocksPerPlayer()).thenReturn(100);
        var store = new ProfileFiles(folder, logger);
        try (var profiles = new ProfileRepository(store, logger);
             var locks = new ContainerLockService(mock(ContainerLockDataStore.class), logger)) {
            var lock = locks.create(ownerId, Set.of(block)).orElseThrow();
            locks.rename(lock, "Workshop");
            var notifications = new NotificationService(profiles, Clock.systemUTC());
            var command = new LockCommand(server, resolver, locks, mock(MessageService.class),
                    mock(FeedbackService.class), new SettingsService(settings), null, notifications);

            assertTrue(command.onCommand(actor, mock(Command.class), "lock", new String[]{"transfer", "Recipient"}));

            assertEquals(recipientId, lock.ownerId());
            var previousOwnerInbox = profiles.load(ownerId).notifications();
            assertEquals(ownerActs ? 0 : 1, previousOwnerInbox.size());
            if (!ownerActs) {
                assertEquals(NotificationType.LOCK_ADMIN_CHANGED, previousOwnerInbox.getFirst().type());
                assertEquals("Admin", previousOwnerInbox.getFirst().actor());
                assertEquals("transferred:Recipient", previousOwnerInbox.getFirst().detail());
            }
            var recipientInbox = profiles.load(recipientId).notifications();
            assertEquals(1, recipientInbox.size());
            assertEquals(NotificationType.LOCK_TRANSFERRED, recipientInbox.getFirst().type());
            assertEquals(actorName, recipientInbox.getFirst().actor());
            assertEquals("Workshop", recipientInbox.getFirst().detail());
            assertTrue(profiles.flush(Duration.ofSeconds(5)));
            assertEquals(ownerActs ? 0 : 1, store.load(ownerId).notifications().size());
            assertEquals(1, store.load(recipientId).notifications().size());
        }
    }
}
