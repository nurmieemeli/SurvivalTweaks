package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class NotificationCenterController implements Listener {

    private static final int CLEAR_READ_SLOT = 48;
    private static final int BACK_SLOT = 49;

    private final Server server;
    private final ProfileRepository profiles;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private Consumer<Player> back = Player::closeInventory;

    public NotificationCenterController(
            Server server,
            ProfileRepository profiles,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.server = server;
        this.profiles = profiles;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void open(Player player) {
        List<PlayerNotification> notifications = profiles.load(player.getUniqueId()).notifications();
        NotificationsHolder holder = new NotificationsHolder(
                player.getUniqueId(),
                notifications.stream().limit(45).toList(),
                server,
                messages.component(
                        player,
                        "ui.notifications.title",
                        Placeholder.unparsed(
                                "unread",
                                Long.toString(notifications.stream().filter(value -> !value.read()).count())
                        )
                )
        );
        for (int index = 0; index < holder.notifications().size(); index++) {
            PlayerNotification notification = holder.notifications().get(index);
            long minutes = Math.max(
                    0L,
                    Duration.between(notification.createdAt(), clock.instant()).toMinutes()
            );
            holder.getInventory().setItem(index, item(
                    material(notification.type(), notification.read()),
                    messages.component(
                            player,
                            "ui.notifications.type."
                                    + notification.type().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                            Placeholder.unparsed("actor", notification.actor()),
                            Placeholder.unparsed("detail", notification.detail())
                    ),
                    List.of(
                            messages.component(
                                    player,
                                    "ui.notifications.age",
                                    Placeholder.unparsed("minutes", Long.toString(minutes))
                            ),
                            messages.component(
                                    player,
                                    notification.read()
                                            ? "ui.notifications.read"
                                            : "ui.notifications.unread"
                            ),
                            messages.component(player, "ui.notifications.actions")
                    )
            ));
        }
        holder.getInventory().setItem(CLEAR_READ_SLOT, item(
                Material.LAVA_BUCKET,
                messages.component(player, "ui.notifications.clear-read"),
                List.of()
        ));
        holder.getInventory().setItem(BACK_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        player.openInventory(holder.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof NotificationsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        int slot = event.getRawSlot();
        var profile = profiles.load(player.getUniqueId());
        if (slot == BACK_SLOT) {
            back.accept(player);
            return;
        }
        if (slot == CLEAR_READ_SLOT) {
            profile.clearReadNotifications();
            profiles.save(profile);
            feedback.play(player, FeedbackService.UI_CLICK);
            open(player);
            return;
        }
        if (slot < 0 || slot >= holder.notifications().size()) {
            return;
        }
        UUID notificationId = holder.notifications().get(slot).id();
        if (event.isShiftClick()) {
            profile.removeNotification(notificationId);
        } else {
            profile.markNotificationRead(notificationId);
        }
        profiles.save(profile);
        feedback.play(player, FeedbackService.UI_CLICK);
        open(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof NotificationsHolder) {
            event.setCancelled(true);
        }
    }

    private Material material(NotificationType type, boolean read) {
        if (read) {
            return Material.PAPER;
        }
        return switch (type) {
            case TELEPORT_DECLINED, TELEPORT_EXPIRED -> Material.ENDER_PEARL;
            case LOCK_ACCESS_DENIED, LOCK_ADMIN_CHANGED -> Material.IRON_DOOR;
            case LOCK_TRANSFERRED -> Material.NAME_TAG;
            case DEATH_MARKER_EXPIRED -> Material.COMPASS;
            case MAIL -> Material.WRITABLE_BOOK;
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

    private static final class NotificationsHolder implements InventoryHolder {

        private final UUID ownerId;
        private final List<PlayerNotification> notifications;
        private final Inventory inventory;

        private NotificationsHolder(
                UUID ownerId,
                List<PlayerNotification> notifications,
                Server server,
                Component title
        ) {
            this.ownerId = ownerId;
            this.notifications = List.copyOf(notifications);
            inventory = server.createInventory(this, 54, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        private List<PlayerNotification> notifications() {
            return notifications;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
