package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.service.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class TextPromptService implements Listener {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public TextPromptService(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void request(Player player, String messageKey, BiConsumer<Player, String> response,
                        TagResolver... placeholders) {
        UUID playerId = player.getUniqueId();
        cancel(prompts.remove(playerId));
        player.closeInventory();
        messages.send(player, messageKey, placeholders);
        Prompt prompt = new Prompt(player, response);
        prompt.timeout = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (prompt.claimed.compareAndSet(false, true) && prompts.remove(playerId, prompt)
                            && player.isOnline()) {
                        messages.send(player, "ui.text-prompt.expired");
                    }
                },
                20L * 60L
        );
        prompts.put(playerId, prompt);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Prompt prompt = prompts.get(playerId);
        if (prompt == null || prompt.player != player || !prompt.claimed.compareAndSet(false, true)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> {
                    if (prompts.remove(playerId, prompt)) {
                        cancel(prompt);
                        if (player.isOnline()) {
                            prompt.response.accept(player, text);
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Prompt prompt = prompts.get(playerId);
        if (prompt != null && prompt.player == player && prompts.remove(playerId, prompt)) {
            cancel(prompt);
        }
    }

    private void cancel(Prompt prompt) {
        if (prompt != null) {
            prompt.timeout.cancel();
        }
    }

    private static final class Prompt {
        private final Player player;
        private final BiConsumer<Player, String> response;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private BukkitTask timeout;

        private Prompt(Player player, BiConsumer<Player, String> response) {
            this.player = player;
            this.response = response;
        }
    }
}
