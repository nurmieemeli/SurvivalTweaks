package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LockTargetStatusServiceTest {

    @Test
    void updatesLanguageAndNewlyResolvedOwnerForTheSameLock() {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var player = mock(Player.class);
        var owner = mock(OfflinePlayer.class);
        var resolver = mock(ContainerBlockResolver.class);
        var locks = mock(ContainerLockService.class);
        var experience = mock(PlayerExperienceService.class);
        var playerId = UUID.randomUUID();
        var ownerId = UUID.randomUUID();
        var blocks = Set.of(new BlockKey(UUID.randomUUID(), 0, 64, 0));
        Logger logger = Logger.getAnonymousLogger();
        Clock clock = mock(Clock.class);
        var settings = new SettingsService(PluginSettings.load(new YamlConfiguration(), logger));
        var preference = new AtomicReference<>(LanguagePreference.ENGLISH);
        var messages = new MessageService(Map.of("lock.actionbar.denied", "Locked by <owner>"),
                Map.of("lock.actionbar.denied", "Lukitsija <owner>"), logger);
        messages.languagePreference(id -> preference.get());

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenAnswer(invocation -> List.of(player));
        when(server.getPlayer(playerId)).thenReturn(player);
        when(server.getOfflinePlayer(ownerId)).thenReturn(owner);
        when(player.getUniqueId()).thenReturn(playerId);
        when(owner.getName()).thenReturn(null);
        when(experience.actionBars(player)).thenReturn(true);
        when(locks.lockCount()).thenReturn(1);
        when(locks.singleLockFor(blocks)).thenReturn(Optional.of(ContainerLock.create(ownerId, blocks)));
        when(resolver.target(player, settings.current().lockTargetDistance()))
                .thenReturn(Optional.of(new ContainerBlockResolver.Target(null, blocks)));

        var tick = ArgumentCaptor.forClass(Runnable.class);
        try (var hints = new LockTargetStatusService(plugin, resolver, locks, messages, settings,
                new ActionBarService(clock), mock(SafeTeleportService.class), experience,
                mock(ProfileRepository.class), clock, new TaskFailureIsolation(logger, Clock.systemUTC()))) {
            verify(scheduler).runTaskTimer(eq(plugin), tick.capture(), eq(20L), eq(20L));
            tick.getValue().run();
            preference.set(LanguagePreference.FINNISH);
            tick.getValue().run();
            tick.getValue().run();
            verify(server).getOfflinePlayer(ownerId);

            when(owner.getName()).thenReturn("Owner");
            when(clock.millis()).thenReturn(5_000L);
            tick.getValue().run();
            tick.getValue().run();
            verify(server, times(2)).getOfflinePlayer(ownerId);
            when(owner.getName()).thenReturn("RenamedOwner");
            when(clock.millis()).thenReturn(6_000L);
            tick.getValue().run();
            verify(server, times(2)).getOfflinePlayer(ownerId);
            when(clock.millis()).thenReturn(65_000L);
            tick.getValue().run();
            verify(server, times(3)).getOfflinePlayer(ownerId);

            var sent = ArgumentCaptor.forClass(Component.class);
            verify(player, times(4)).sendActionBar(sent.capture());
            var plain = PlainTextComponentSerializer.plainText();
            String abbreviatedId = ownerId.toString().substring(0, 8);
            assertEquals(List.of("Locked by " + abbreviatedId, "Lukitsija " + abbreviatedId, "Lukitsija Owner",
                            "Lukitsija RenamedOwner"),
                    sent.getAllValues().stream().map(plain::serialize).toList());
        }
    }
}
