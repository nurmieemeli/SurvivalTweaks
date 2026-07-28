package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
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
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class JourneyMenuController implements Listener {

    private static final List<OnboardingHint> OBJECTIVES = List.of(
            OnboardingHint.WELCOME,
            OnboardingHint.HUB,
            OnboardingHint.LANGUAGE,
            OnboardingHint.HOME,
            OnboardingHint.TELEPORT_INBOX,
            OnboardingHint.LOCK_CONTROL
    );
    private static final int[] SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int VANILLA_GUIDE = 13;
    private static final int BACK = 22;

    private final Server server;
    private final ProfileRepository profiles;
    private final VanillaGuideController vanillaGuide;
    private final MessageService messages;
    private final FeedbackService feedback;
    private Consumer<Player> back = Player::closeInventory;

    public JourneyMenuController(
            Server server,
            ProfileRepository profiles,
            VanillaGuideController vanillaGuide,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.server = server;
        this.profiles = profiles;
        this.vanillaGuide = vanillaGuide;
        this.messages = messages;
        this.feedback = feedback;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void open(Player player) {
        var profile = profiles.load(player.getUniqueId());
        long completed = OBJECTIVES.stream().filter(profile::hintSeen).count();
        JourneyHolder holder = new JourneyHolder(
                player.getUniqueId(),
                server,
                messages.component(
                        player,
                        "ui.journey.title",
                        Placeholder.unparsed("completed", Long.toString(completed)),
                        Placeholder.unparsed("total", Integer.toString(OBJECTIVES.size()))
                )
        );
        for (int index = 0; index < OBJECTIVES.size(); index++) {
            OnboardingHint objective = OBJECTIVES.get(index);
            boolean done = profile.hintSeen(objective);
            String key = objective.name().toLowerCase(Locale.ROOT).replace('_', '-');
            holder.getInventory().setItem(SLOTS[index], item(
                    done ? Material.LIME_DYE : Material.GRAY_DYE,
                    messages.component(player, "ui.journey.objective." + key),
                    List.of(
                            messages.component(player, "ui.journey.description." + key),
                            messages.component(player, done
                                    ? "ui.journey.complete"
                                    : "ui.journey.pending")
                    )
            ));
        }
        holder.getInventory().setItem(VANILLA_GUIDE, item(
                Material.KNOWLEDGE_BOOK,
                messages.component(player, "ui.journey.vanilla-guide"),
                List.of(messages.component(player, "ui.journey.vanilla-guide-description"))
        ));
        holder.getInventory().setItem(BACK, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        player.openInventory(holder.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof JourneyHolder holder)) {
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
        } else if (event.getRawSlot() == VANILLA_GUIDE) {
            feedback.play(player, FeedbackService.UI_CLICK);
            vanillaGuide.open(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof JourneyHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class JourneyHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Inventory inventory;

        private JourneyHolder(UUID ownerId, Server server, Component title) {
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
