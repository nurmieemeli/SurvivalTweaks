package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.service.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextPromptServiceTest {
    @Test
    void queuedReplyMustStillBelongToTheCurrentPromptAndSession() {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var player = mock(Player.class);
        var mainTasks = new ArrayDeque<Runnable>();
        var replies = new ArrayList<String>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            mainTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        var prompts = new TextPromptService(plugin, mock(MessageService.class));
        var chat = mock(AsyncChatEvent.class);
        when(chat.getPlayer()).thenReturn(player);
        when(chat.message()).thenReturn(Component.text("old reply"), Component.text("current reply"),
                Component.text("after quit"));
        prompts.request(player, "lock-transfer", (sender, text) -> replies.add(text));
        prompts.onChat(chat);
        prompts.request(player, "lock-search", (sender, text) -> replies.add(text));
        mainTasks.remove().run();
        prompts.onChat(chat);
        prompts.onChat(chat);
        mainTasks.remove().run();
        assertEquals(List.of("current reply"), replies);
        assertEquals(0, mainTasks.size());

        prompts.request(player, "lock-transfer", (sender, text) -> replies.add(text));
        prompts.onChat(chat);
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        prompts.onQuit(quit);
        mainTasks.remove().run();
        assertEquals(List.of("current reply"), replies);
    }
}
