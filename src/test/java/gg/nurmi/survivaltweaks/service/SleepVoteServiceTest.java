package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SleepVoteServiceTest {

    @Test
    void vetoLeavesWeatherAloneAndDoesNotRetryUntilTheVoteChanges(@TempDir Path worldFolder) {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var world = mock(World.class);
        var bed = mock(Block.class);
        var sleeper = mock(Player.class);
        var settings = mock(PluginSettings.class);
        var messages = mock(MessageService.class);
        var task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
        when(world.getPlayers()).thenReturn(List.of(sleeper));
        when(world.getFullTime()).thenReturn(14_000L);
        when(bed.getWorld()).thenReturn(world);
        when(sleeper.getUniqueId()).thenReturn(UUID.randomUUID());
        when(sleeper.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(sleeper.isSleeping()).thenReturn(true);
        when(settings.sleepVotingEnabled()).thenReturn(true);
        when(settings.sleepRequiredPercentage()).thenReturn(50);
        when(settings.sleepClearWeather()).thenReturn(true);
        var leave = mock(PlayerBedLeaveEvent.class);
        when(leave.getBed()).thenReturn(bed);
        when(leave.getPlayer()).thenReturn(sleeper);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(20L)))
                .thenReturn(task);

        try (var service = new SleepVoteService(plugin, new SettingsService(settings), messages,
                mock(ActionBarService.class), mock(PlayerExperienceService.class), mock(PlayerListService.class))) {
            service.onBedEnter(mock(PlayerBedEnterEvent.class));
            var evaluation = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskTimer(eq(plugin), evaluation.capture(), eq(1L), eq(20L));
            evaluation.getValue().run();
            evaluation.getValue().run();

            verify(world).setFullTime(24_000L);
            verify(world, never()).setStorm(false);
            verify(messages, never()).send(sleeper, "sleep.skipped");

            service.onBedLeave(leave);
            when(world.getFullTime()).thenReturn(14_000L, 24_000L);
            evaluation.getValue().run();

            verify(world, times(2)).setFullTime(24_000L);
            verify(world).setStorm(false);
            verify(messages).send(sleeper, "sleep.skipped");
        }
    }

    @Test
    void disablingVotingStopsAnAlreadyQueuedQuitEvaluation(@TempDir Path worldFolder) {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var repeatingTask = mock(BukkitTask.class);
        var world = mock(World.class);
        var sleeper = mock(Player.class);
        var enabled = mock(PluginSettings.class);
        var disabled = mock(PluginSettings.class);
        var settings = new SettingsService(enabled);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
        when(world.getPlayers()).thenReturn(List.of(sleeper));
        when(world.getFullTime()).thenReturn(14_000L);
        when(sleeper.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(sleeper.isSleeping()).thenReturn(true);
        when(enabled.sleepVotingEnabled()).thenReturn(true);
        when(disabled.sleepRequiredPercentage()).thenReturn(50);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(20L))).thenReturn(repeatingTask);
        try (var service = new SleepVoteService(plugin, settings, mock(MessageService.class),
                mock(ActionBarService.class), mock(PlayerExperienceService.class), mock(PlayerListService.class))) {
            service.onBedEnter(mock(PlayerBedEnterEvent.class));
            service.onQuit(mock(PlayerQuitEvent.class));
            var queued = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(plugin), queued.capture());
            settings.apply(disabled);
            service.reconfigure();
            verify(repeatingTask).cancel();

            queued.getValue().run();

            verify(world, never()).setFullTime(anyLong());
            verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(20L));
        }
    }

    @Test
    void nobodyAwakeMeansNobodyIsRequired() {
        assertEquals(0, SleepVoteService.requiredSleepers(0, 50));
        assertEquals(0, SleepVoteService.requiredSleepers(0, 100));
    }

    @Test
    void aSinglePlayerAlwaysNeedsToSleepThemselves() {
        assertEquals(1, SleepVoteService.requiredSleepers(1, 1));
        assertEquals(1, SleepVoteService.requiredSleepers(1, 50));
        assertEquals(1, SleepVoteService.requiredSleepers(1, 100));
    }

    @Test
    void theThresholdRoundsUpSoAMajorityIsNeverUndercounted() {
        // 50% of 5 is 2.5 — three players must sleep, not two.
        assertEquals(3, SleepVoteService.requiredSleepers(5, 50));
        assertEquals(2, SleepVoteService.requiredSleepers(4, 50));
        assertEquals(2, SleepVoteService.requiredSleepers(3, 50));
        assertEquals(4, SleepVoteService.requiredSleepers(10, 35));
    }

    @Test
    void exactDivisionsDoNotGainAnExtraSleeper() {
        assertEquals(5, SleepVoteService.requiredSleepers(10, 50));
        assertEquals(1, SleepVoteService.requiredSleepers(10, 10));
        assertEquals(10, SleepVoteService.requiredSleepers(10, 100));
    }

    @Test
    void aVeryLowPercentageStillRequiresOneSleeper() {
        assertEquals(1, SleepVoteService.requiredSleepers(20, 1));
        assertEquals(1, SleepVoteService.requiredSleepers(99, 1));
        assertEquals(2, SleepVoteService.requiredSleepers(101, 1));
    }

    @Test
    void largePopulationsDoNotOverflowTheIntermediateMultiplication() {
        assertEquals(Integer.MAX_VALUE / 100 + 1, SleepVoteService.requiredSleepers(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, SleepVoteService.requiredSleepers(Integer.MAX_VALUE, 100));
    }

    @Test
    void impossibleInputsAreRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(-1, 50));
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(5, 0));
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(5, 101));
    }
}
