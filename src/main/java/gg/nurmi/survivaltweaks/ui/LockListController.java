package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class LockListController implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final Server server;
    private final ContainerLockService locks;
    private final LockControlPanelController panels;
    private final MessageService messages;
    private final FeedbackService feedback;
    private Consumer<Player> back = Player::closeInventory;

    public LockListController(
            Server server,
            ContainerLockService locks,
            LockControlPanelController panels,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.server = server;
        this.locks = locks;
        this.panels = panels;
        this.messages = messages;
        this.feedback = feedback;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int page) {
        List<ContainerLock> managed = player.hasPermission(LockCommand.ADMIN_PERMISSION)
                ? locks.allLocks()
                : locks.locksForOwner(player.getUniqueId());
        int pages = Math.max(1, (managed.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selectedPage = Math.max(0, Math.min(page, pages - 1));
        List<UUID> visible = managed.stream()
                .skip((long) selectedPage * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .map(ContainerLock::id)
                .toList();
        LockListHolder holder = new LockListHolder(
                player.getUniqueId(),
                visible,
                selectedPage,
                pages,
                server,
                messages.component(
                        player,
                        "ui.lock-list.title",
                        Placeholder.unparsed("page", Integer.toString(selectedPage + 1)),
                        Placeholder.unparsed("pages", Integer.toString(pages))
                )
        );
        for (int index = 0; index < visible.size(); index++) {
            ContainerLock lock = locks.lock(visible.get(index)).orElseThrow();
            BlockKey block = lock.blocks().iterator().next();
            String name = lock.name().isBlank()
                    ? messages.plain(player, "ui.lock-panel.default-name")
                    : lock.name();
            holder.getInventory().setItem(index, item(
                    Material.CHEST,
                    messages.component(
                            player,
                            "ui.lock-list.lock",
                            Placeholder.unparsed("name", name)
                    ),
                    List.of(
                            messages.component(
                                    player,
                                    "ui.lock-list.coordinates",
                                    Placeholder.unparsed("x", Integer.toString(block.x())),
                                    Placeholder.unparsed("y", Integer.toString(block.y())),
                                    Placeholder.unparsed("z", Integer.toString(block.z()))
                            ),
                            messages.component(player, "ui.lock-list.action")
                    )
            ));
        }
        if (selectedPage > 0) {
            holder.getInventory().setItem(PREVIOUS, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.previous"),
                    List.of()
            ));
        }
        holder.getInventory().setItem(BACK, item(
                Material.BARRIER,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        if (selectedPage + 1 < pages) {
            holder.getInventory().setItem(NEXT, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.next"),
                    List.of()
            ));
        }
        player.openInventory(holder.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof LockListHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BACK) {
            back.accept(player);
        } else if (slot == PREVIOUS && holder.page() > 0) {
            open(player, holder.page() - 1);
        } else if (slot == NEXT && holder.page() + 1 < holder.pages()) {
            open(player, holder.page() + 1);
        } else if (slot >= 0 && slot < holder.lockIds().size()) {
            locks.lock(holder.lockIds().get(slot)).ifPresent(lock -> panels.open(player, lock));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof LockListHolder) {
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

    private static final class LockListHolder implements SurvivalTweaksMenu {

        private final UUID ownerId;
        private final List<UUID> lockIds;
        private final int page;
        private final int pages;
        private final Inventory inventory;

        private LockListHolder(
                UUID ownerId,
                List<UUID> lockIds,
                int page,
                int pages,
                Server server,
                Component title
        ) {
            this.ownerId = ownerId;
            this.lockIds = List.copyOf(lockIds);
            this.page = page;
            this.pages = pages;
            this.inventory = server.createInventory(this, 54, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        private List<UUID> lockIds() {
            return lockIds;
        }

        private int page() {
            return page;
        }

        private int pages() {
            return pages;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
