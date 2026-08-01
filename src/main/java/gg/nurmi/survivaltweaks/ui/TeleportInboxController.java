package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.command.teleport.TeleportAcceptCommand;
import gg.nurmi.survivaltweaks.object.TeleportRequest;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class TeleportInboxController implements Listener, CommandExecutor {

    private static final int CLOSE_SLOT = 49;

    private final Server server;
    private final TeleportRequestService requests;
    private final TeleportAcceptCommand actions;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private Consumer<Player> back = Player::closeInventory;

    public TeleportInboxController(
            Server server,
            TeleportRequestService requests,
            TeleportAcceptCommand actions,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.server = server;
        this.requests = requests;
        this.actions = actions;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public boolean open(Player player) {
        List<TeleportRequest> incoming = requests.incomingRequests(player.getUniqueId(), clock.instant());
        if (incoming.isEmpty()) {
            messages.send(player, "teleport.accept.none");
            return false;
        }
        Inbox inventory = new Inbox(
                player.getUniqueId(),
                incoming.stream().limit(45).toList(),
                server,
                messages.component(
                        player,
                        "ui.teleport-inbox.title",
                        Placeholder.unparsed("count", Integer.toString(incoming.size()))
                )
        );
        for (int index = 0; index < inventory.requests().size(); index++) {
            TeleportRequest request = inventory.requests().get(index);
            Player sender = server.getPlayer(request.senderId());
            String name = sender == null ? request.senderId().toString().substring(0, 8) : sender.getName();
            long seconds = Math.max(
                    1L,
                    (Duration.between(clock.instant(), request.expiresAt()).toMillis() + 999L) / 1_000L
            );
            inventory.getInventory().setItem(index, playerHead(
                    request.senderId(),
                    messages.component(
                            player,
                            "ui.teleport-inbox.request",
                            Placeholder.unparsed("player", name)
                    ),
                    List.of(
                            messages.component(
                                    player,
                                    MessageService.plural("ui.teleport-inbox.expires", seconds),
                                    Placeholder.unparsed("seconds", Long.toString(seconds))
                            ),
                            messages.component(player, "ui.teleport-inbox.actions")
                    )
            ));
        }
        inventory.getInventory().setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                messages.component(player, "ui.home-menu.close"),
                List.of()
        ));
        player.openInventory(inventory.getInventory());
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
            messages.send(player, "teleport.inbox-usage");
            return true;
        }
        open(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Inbox inbox)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(inbox.ownerId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            back.accept(player);
            return;
        }
        if (slot < 0 || slot >= inbox.requests().size()) {
            return;
        }
        TeleportRequest request = inbox.requests().get(slot);
        if (event.isRightClick()) {
            actions.declineRequest(player, request);
        } else {
            actions.acceptRequest(player, request);
        }
        if (player.isOnline() && !open(player)) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Inbox) {
            event.setCancelled(true);
        }
    }

    private ItemStack playerHead(UUID playerId, Component name, List<Component> lore) {
        ItemStack item = item(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(server.getOfflinePlayer(playerId));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class Inbox implements SurvivalTweaksMenu {

        private final UUID ownerId;
        private final List<TeleportRequest> requests;
        private final Inventory inventory;

        private Inbox(
                UUID ownerId,
                List<TeleportRequest> requests,
                Server server,
                Component title
        ) {
            this.ownerId = ownerId;
            this.requests = List.copyOf(requests);
            this.inventory = server.createInventory(this, 54, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        private List<TeleportRequest> requests() {
            return requests;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
