package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

final class HomeMenu implements InventoryHolder {

    static final int PAGE_SIZE = 45;
    static final int PREVIOUS_SLOT = 45;
    static final int CLOSE_SLOT = 49;
    static final int NEXT_SLOT = 53;

    private final UUID ownerId;
    private final List<Home> homes;
    private final int page;
    private final Inventory inventory;

    HomeMenu(
            Server server,
            MessageService messages,
            Player owner,
            List<Home> homes,
            int requestedPage
    ) {
        this.ownerId = owner.getUniqueId();
        this.homes = homes.stream()
                .sorted(HomeMenu::compareHomes)
                .toList();
        this.page = clampedPage(requestedPage, this.homes.size());
        this.inventory = server.createInventory(
                this,
                54,
                messages.component(
                        owner,
                        "ui.home-menu.title",
                        Placeholder.unparsed("page", Integer.toString(page + 1)),
                        Placeholder.unparsed("pages", Integer.toString(pageCount()))
                )
        );
        populate(messages, owner);
    }

    UUID ownerId() {
        return ownerId;
    }

    int page() {
        return page;
    }

    boolean hasPreviousPage() {
        return page > 0;
    }

    boolean hasNextPage() {
        return page + 1 < pageCount();
    }

    Optional<Home> homeAt(int rawSlot) {
        OptionalInt index = homeIndexAt(page, rawSlot, homes.size());
        return index.isPresent() ? Optional.of(homes.get(index.getAsInt())) : Optional.empty();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private int pageCount() {
        return pageCount(homes.size());
    }

    static int pageCount(int homeCount) {
        return Math.max(1, (homeCount + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    static int clampedPage(int requestedPage, int homeCount) {
        return Math.max(0, Math.min(requestedPage, pageCount(homeCount) - 1));
    }

    static OptionalInt homeIndexAt(int page, int rawSlot, int homeCount) {
        if (page < 0 || rawSlot < 0 || rawSlot >= PAGE_SIZE) {
            return OptionalInt.empty();
        }
        int index = page * PAGE_SIZE + rawSlot;
        return index < homeCount ? OptionalInt.of(index) : OptionalInt.empty();
    }

    private void populate(MessageService messages, Player owner) {
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, homes.size());
        for (int index = start; index < end; index++) {
            Home home = homes.get(index);
            inventory.setItem(index - start, homeItem(messages, owner, home));
        }

        if (hasPreviousPage()) {
            inventory.setItem(PREVIOUS_SLOT, named(
                    Material.ARROW,
                    messages.component(owner, "ui.home-menu.previous")
            ));
        }
        inventory.setItem(CLOSE_SLOT, named(
                Material.BARRIER,
                messages.component(owner, "ui.home-menu.close")
        ));
        if (hasNextPage()) {
            inventory.setItem(NEXT_SLOT, named(
                    Material.ARROW,
                    messages.component(owner, "ui.home-menu.next")
            ));
        }
    }

    private ItemStack homeItem(MessageService messages, Player owner, Home home) {
        ItemStack item = new ItemStack(home.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component(
                owner,
                "ui.home-menu.home-name",
                Placeholder.unparsed("home", home.name())
        ));
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (home.favorite()) {
            lore.add(messages.component(owner, "ui.home-menu.favorite"));
        }
        if (!home.description().isBlank()) {
            lore.add(messages.component(
                    owner,
                    "ui.home-menu.home-description",
                    Placeholder.unparsed("description", home.description())
            ));
        }
        lore.add(messages.component(
                owner,
                "ui.home-menu.home-category",
                Placeholder.component(
                        "category",
                        messages.component(
                                owner,
                                "ui.home-editor.category." + home.category().name().toLowerCase()
                        )
                )
        ));
        lore.add(messages.component(
                        owner,
                        "ui.home-menu.home-world",
                        Placeholder.unparsed("world", home.worldName())
                ));
        lore.add(messages.component(
                        owner,
                        "ui.home-menu.home-coordinates",
                        Placeholder.unparsed("x", Long.toString(Math.round(home.x()))),
                        Placeholder.unparsed("y", Long.toString(Math.round(home.y()))),
                        Placeholder.unparsed("z", Long.toString(Math.round(home.z())))
                ));
        lore.add(messages.component(owner, "ui.home-menu.home-action"));
        lore.add(messages.component(owner, "ui.home-menu.home-edit-action"));
        lore.add(messages.component(owner, "ui.home-menu.home-reorder-action"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static int compareHomes(Home left, Home right) {
        int favorite = Boolean.compare(right.favorite(), left.favorite());
        if (favorite != 0) {
            return favorite;
        }
        int category = Integer.compare(left.category().ordinal(), right.category().ordinal());
        if (category != 0) {
            return category;
        }
        int order = Integer.compare(left.order(), right.order());
        return order != 0 ? order : left.name().compareToIgnoreCase(right.name());
    }

    private ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        item.setItemMeta(meta);
        return item;
    }
}
