package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ReloadService;
import gg.nurmi.survivaltweaks.storage.StorageBackend;
import gg.nurmi.survivaltweaks.storage.StorageConfiguration;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageCommandTest {
    @TempDir
    Path folder;

    @Test
    void storageTestUsesUnloadedDiskEditsDespiteUnrelatedErrorsWithoutPublishingThem() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getResource(anyString())).thenAnswer(call ->
                getClass().getResourceAsStream("/" + call.getArgument(0)));
        YamlConfiguration active = new YamlConfiguration();
        active.set("storage.backend", "sqlite");
        when(plugin.getConfig()).thenReturn(active);
        YamlConfiguration edited = new YamlConfiguration();
        edited.set("home.max-amount", -1);
        edited.set("storage.remote.type", "postgresql");
        edited.set("storage.remote.database", "edited_destination");
        Path file = folder.resolve("config.yml");
        edited.save(file.toFile());
        byte[] original = Files.readAllBytes(file);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        StorageManager storage = mock(StorageManager.class);
        when(storage.dataFolder()).thenReturn(folder);
        when(storage.testBackend(any())).thenReturn(new StorageManager.TestResult(StorageBackend.POSTGRESQL, true, 1));
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        var reloads = new ReloadService(plugin, null, null, null, null, null);
        var command = new SurvivalTweaksCommand(mock(MessageService.class), reloads, null, null, null,
                null, null, null, null, null, null, null, null, storage, null, null, null, null, plugin);

        assertTrue(command.onCommand(sender, mock(Command.class), "survivaltweaks",
                new String[]{"storage", "test", "postgresql"}));

        ArgumentCaptor<StorageConfiguration> tested = ArgumentCaptor.forClass(StorageConfiguration.class);
        verify(storage).testBackend(tested.capture());
        assertEquals("edited_destination", tested.getValue().database());
        assertEquals(StorageBackend.POSTGRESQL, tested.getValue().backend());
        assertEquals("sqlite", active.getString("storage.backend"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}
