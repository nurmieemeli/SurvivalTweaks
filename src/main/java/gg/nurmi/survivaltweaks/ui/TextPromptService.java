package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.service.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class TextPromptService implements Listener {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public TextPromptService(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void request(Player player, String messageKey, BiConsumer<Player, String> response) {
        cancel(player.getUniqueId());
        player.closeInventory();
        messages.send(player, messageKey);
        BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (prompts.remove(player.getUniqueId()) != null && player.isOnline()) {
                        messages.send(player, "ui.text-prompt.expired");
                    }
                },
                20L * 60L
        );
        prompts.put(player.getUniqueId(), new Prompt(response, timeout));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        prompt.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> prompt.response().accept(event.getPlayer(), text)
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private void cancel(UUID playerId) {
        Prompt prompt = prompts.remove(playerId);
        if (prompt != null) {
            prompt.timeout().cancel();
        }
    }

    private record Prompt(BiConsumer<Player, String> response, BukkitTask timeout) {
    }
}
