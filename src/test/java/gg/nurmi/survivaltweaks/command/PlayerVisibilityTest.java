package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.command.home.HomeCommand;
import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.command.teleport.TeleportCommand;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerStatisticsService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.storage.ContainerLockDataStore;
import gg.nurmi.survivaltweaks.ui.SocialProfileController;
import gg.nurmi.survivaltweaks.ui.StatisticsJournalController;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerVisibilityTest {

    @Test
    void homeInviteAndUninviteSaveAccessAndOnlyInviteNotifiesTheGuest() {
        var server = mock(Server.class);
        var profiles = mock(ProfileRepository.class);
        var messages = mock(MessageService.class);
        var owner = mock(Player.class);
        var guest = mock(Player.class);
        UUID ownerId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        var profile = new Profile(ownerId);
        profile.addHome(new Home("base", UUID.randomUUID(), "world", 0, 64, 0, 0, 0));
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(owner.getName()).thenReturn("Owner");
        when(guest.getUniqueId()).thenReturn(guestId);
        when(guest.getName()).thenReturn("Guest");
        when(guest.getPlayer()).thenReturn(guest);
        when(guest.isOnline()).thenReturn(true);
        when(server.getPlayerExact("Guest")).thenReturn(guest);
        when(profiles.load(ownerId)).thenReturn(profile);
        var command = new HomeCommand(profiles, messages, null, null, server, null);
        var metadata = mock(Command.class);

        command.onCommand(owner, metadata, "home", new String[]{"invite", "base", "Guest"});
        assertTrue(profile.home("base").orElseThrow().isSharedWith(guestId));
        command.onCommand(owner, metadata, "home", new String[]{"uninvite", "base", "Guest"});
        assertFalse(profile.home("base").orElseThrow().isSharedWith(guestId));

        verify(profiles, times(2)).save(profile);
        verify(messages).send(eq(owner), eq("home.invited"), any(), any());
        verify(messages).send(eq(owner), eq("home.uninvited"), any(), any());
        verify(messages).send(eq(guest), eq("home.invite-received"), any(), any());
    }

    @Test
    void onlyUntrustFindsTheSavedPlayerAfterPaperForgetsTheirName() {
        var server = mock(Server.class);
        var profiles = mock(ProfileRepository.class);
        var messages = mock(MessageService.class);
        var owner = mock(Player.class);
        var target = mock(OfflinePlayer.class);
        UUID ownerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var profile = new Profile(ownerId);
        profile.trustPlayer(targetId);
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(target.getUniqueId()).thenReturn(targetId);
        when(profiles.load(ownerId)).thenReturn(profile);
        when(profiles.playerIdByName("OfflineFriend")).thenReturn(targetId);
        when(server.getOfflinePlayer(targetId)).thenReturn(target);

        var executor = new TrustCommand(server, profiles, messages);
        var untrust = mock(Command.class);
        when(untrust.getName()).thenReturn("untrust");
        executor.onCommand(owner, untrust, "untrust", new String[]{"OfflineFriend"});
        assertFalse(profile.isTrusted(targetId));

        var trust = mock(Command.class);
        when(trust.getName()).thenReturn("trust");
        executor.onCommand(owner, trust, "trust", new String[]{"OfflineFriend"});

        assertFalse(profile.isTrusted(targetId));
        verify(profiles).playerIdByName("OfflineFriend");
        verify(profiles).save(profile);
        verify(messages).send(owner, "trust.unknown-player");
    }

    @Test
    void directStatisticsLookupDoesNotReadLiveDataForAHiddenPlayer() {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var viewer = mock(Player.class);
        var hidden = mock(Player.class);
        UUID hiddenId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.hasPermission(SocialProfileController.BYPASS_PERMISSION)).thenReturn(true);
        when(hidden.getUniqueId()).thenReturn(hiddenId);
        when(hidden.isOnline()).thenReturn(true);
        when(server.getPlayerExact("HiddenStaff")).thenReturn(hidden);
        when(server.getPlayer(hiddenId)).thenReturn(hidden);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        var settings = mock(PluginSettings.class);
        when(settings.statisticsEnabled()).thenReturn(true);
        var profiles = mock(ProfileRepository.class);
        var statistics = mock(PlayerStatisticsService.class);
        var messages = mock(MessageService.class);
        when(messages.requirePermission(viewer, "survivaltweaks.command.stats")).thenReturn(true);
        var command = new StatisticsJournalController(plugin, server, new SettingsService(settings), profiles,
                statistics, messages, null, null);

        command.onCommand(viewer, mock(Command.class), "stats", new String[]{"HiddenStaff"});

        verify(messages).send(viewer, "statistics.unavailable");
        verifyNoInteractions(statistics);
    }

    @Test
    void playerSuggestionsPreserveVisibilityAndCommandOrdering() {
        Server server = mock(Server.class);
        Player viewer = mock(Player.class);
        Player hidden = mock(Player.class);
        Player visible = mock(Player.class);
        Player alpha = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(hidden.getUniqueId()).thenReturn(UUID.randomUUID());
        when(visible.getUniqueId()).thenReturn(UUID.randomUUID());
        when(alpha.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getName()).thenReturn("Viewer");
        when(hidden.getName()).thenReturn("HiddenStaff");
        when(visible.getName()).thenReturn("VisiblePlayer");
        when(alpha.getName()).thenReturn("alphaVisible");
        when(viewer.canSee(visible)).thenReturn(true);
        when(viewer.canSee(alpha)).thenReturn(true);
        when(viewer.canSee(viewer)).thenReturn(true);
        doReturn(List.of(viewer, hidden, visible, alpha)).when(server).getOnlinePlayers();
        Command command = mock(Command.class);

        var mail = new MailCommand(server, null, null, null);
        var home = new HomeCommand(null, null, null, null, server, null);
        var lock = new LockCommand(server, null, null, null, null, null, null, null);
        var teleport = new TeleportCommand(server, null, null, null, null, null, null, null, null, null);
        var profile = new SocialProfileController(null, server, null, null, null, null, null, null, null, null, null);
        var statistics = new StatisticsJournalController(null, server, null, null, null, null, null, null);
        var byName = List.of("alphaVisible", "VisiblePlayer");
        var naturalOrder = List.of("VisiblePlayer", "alphaVisible");

        assertEquals(byName, mail.onTabComplete(viewer, command, "mail", new String[]{"send", ""}));
        assertEquals(byName, home.onTabComplete(viewer, command, "home", new String[]{"invite", "base", ""}));
        assertEquals(naturalOrder, lock.onTabComplete(viewer, command, "lock", new String[]{"trust", ""}));
        assertEquals(naturalOrder, lock.onTabComplete(viewer, command, "lock", new String[]{"transfer", ""}));
        assertEquals(naturalOrder, teleport.onTabComplete(viewer, command, "teleport", new String[]{""}));
        assertEquals(List.of("alphaVisible", "Viewer", "VisiblePlayer"),
                profile.onTabComplete(viewer, command, "profile", new String[]{""}));
        assertEquals(List.of("VisiblePlayer"), statistics.onTabComplete(viewer, command, "stats", new String[]{"vIs"}));
        assertEquals(List.of("alphaVisible", "HiddenStaff", "Viewer", "VisiblePlayer"),
                mail.onTabComplete(mock(ConsoleCommandSender.class), command, "mail", new String[]{"send", ""}));
    }

    @Test
    void untrustOnlySuggestsNamesToPlayersWhoCanManageTheLock() {
        var server = mock(Server.class);
        var viewer = mock(Player.class);
        var viewerId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(viewerId);
        var trusted = mock(OfflinePlayer.class);
        var trustedId = UUID.randomUUID();
        when(trusted.getName()).thenReturn("TrustedPlayer");
        when(server.getOfflinePlayer(trustedId)).thenReturn(trusted);
        var settings = mock(PluginSettings.class);
        when(settings.lockTargetDistance()).thenReturn(5);
        var resolver = mock(ContainerBlockResolver.class);
        var block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var ownerId = UUID.randomUUID();
        when(resolver.target(viewer, 5)).thenReturn(Optional.of(
                new ContainerBlockResolver.Target(mock(Block.class), Set.of(block))));

        try (var locks = new ContainerLockService(mock(ContainerLockDataStore.class), Logger.getAnonymousLogger())) {
            var lock = locks.create(ownerId, Set.of(block)).orElseThrow();
            locks.trust(lock, trustedId);
            var command = new LockCommand(server, resolver, locks, null, null,
                    new SettingsService(settings), null, null);
            var metadata = mock(Command.class);
            String[] arguments = {"untrust", ""};
            assertEquals(List.of(), command.onTabComplete(viewer, metadata, "lock", arguments));

            when(viewer.hasPermission(LockCommand.ADMIN_PERMISSION)).thenReturn(true);
            assertEquals(List.of("TrustedPlayer"), command.onTabComplete(viewer, metadata, "lock", arguments));

            locks.transfer(lock, viewerId, 100);
            var oldOwner = mock(OfflinePlayer.class);
            when(oldOwner.getName()).thenReturn("OldOwner");
            when(server.getOfflinePlayer(ownerId)).thenReturn(oldOwner);
            when(viewer.hasPermission(LockCommand.ADMIN_PERMISSION)).thenReturn(false);
            assertEquals(List.of("OldOwner", "TrustedPlayer"), command.onTabComplete(viewer, metadata, "lock", arguments));
        }
    }
}
