package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.VanillaGuideTopic;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class VanillaGuideController implements Listener, CommandExecutor {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int BACK = 22;

    private final Server server;
    private final SettingsService settings;
    private final ProfileRepository profiles;
    private final MessageService messages;
    private final FeedbackService feedback;
    private Consumer<Player> back = Player::closeInventory;

    public VanillaGuideController(
            Server server,
            SettingsService settings,
            ProfileRepository profiles,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.server = server;
        this.settings = settings;
        this.profiles = profiles;
        this.messages = messages;
        this.feedback = feedback;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public boolean open(Player player) {
        if (!settings.current().journeyEnabled() || !settings.current().vanillaGuideEnabled()) {
            messages.send(player, "vanilla-guide.disabled");
            return false;
        }
        var profile = profiles.load(player.getUniqueId());
        List<VanillaGuideTopic> topics = java.util.Arrays.stream(VanillaGuideTopic.values())
                .filter(topic -> settings.current().vanillaGuideTopics().contains(topic.key()))
                .toList();
        long discovered = topics.stream()
                .filter(topic -> profile.hintSeen(topic.hint()))
                .count();
        GuideHolder holder = new GuideHolder(
                player.getUniqueId(),
                server,
                messages.component(
                        player,
                        "ui.vanilla-guide.title",
                        Placeholder.unparsed("discovered", Long.toString(discovered)),
                        Placeholder.unparsed("total", Integer.toString(topics.size()))
                )
        );
        for (int index = 0; index < topics.size(); index++) {
            VanillaGuideTopic topic = topics.get(index);
            boolean found = profile.hintSeen(topic.hint());
            String key = topic.key();
            holder.getInventory().setItem(
                    SLOTS[index],
                    found
                            ? item(
                            material(topic),
                            messages.component(player, "ui.vanilla-guide.topic." + key),
                            List.of(
                                    messages.component(
                                            player,
                                            "ui.vanilla-guide.description." + key
                                    ),
                                    messages.component(player, "ui.vanilla-guide.discovered")
                            )
                    )
                            : item(
                            Material.GRAY_DYE,
                            messages.component(player, "ui.vanilla-guide.undiscovered"),
                            List.of(messages.component(
                                    player,
                                    "ui.vanilla-guide.discovery." + key
                            ))
                    )
            );
        }
        holder.getInventory().setItem(BACK, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        player.openInventory(holder.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
        return true;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (arguments.length != 0) {
            messages.send(player, "vanilla-guide.usage");
            return true;
        }
        open(player);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof GuideHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        if (event.getRawSlot() == BACK) {
            feedback.play(player, FeedbackService.UI_CLICK);
            back.accept(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof GuideHolder) {
            event.setCancelled(true);
        }
    }

    private Material material(VanillaGuideTopic topic) {
        return switch (topic) {
            case NETHER_COORDINATES -> Material.OBSIDIAN;
            case SLEEP_RULES -> Material.RED_BED;
            case VILLAGER_CURING -> Material.GOLDEN_APPLE;
            case RESPAWN_ANCHORS -> Material.RESPAWN_ANCHOR;
            case LODESTONES -> Material.LODESTONE;
            case ANVILS -> Material.ANVIL;
            case ENCHANTING -> Material.ENCHANTING_TABLE;
        };
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class GuideHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Inventory inventory;

        private GuideHolder(UUID ownerId, Server server, Component title) {
            this.ownerId = ownerId;
            this.inventory = server.createInventory(this, 27, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
