package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

final class HomeCustomizationMenu implements SurvivalTweaksMenu {

    static final int ICON_SLOT = 10;
    static final int DESCRIPTION_SLOT = 12;
    static final int FAVORITE_SLOT = 14;
    static final int CATEGORY_SLOT = 16;
    static final int RENAME_SLOT = 19;
    static final int UPDATE_LOCATION_SLOT = 21;
    static final int ARRIVAL_STYLE_SLOT = 23;
    static final int EARLIER_SLOT = 25;
    static final int LATER_SLOT = 26;
    static final int DELETE_SLOT = 31;
    static final int BACK_SLOT = 40;

    private final UUID ownerId;
    private final String homeName;
    private final Inventory inventory;

    HomeCustomizationMenu(Server server, MessageService messages, Player player, Home home) {
        ownerId = player.getUniqueId();
        homeName = home.name();
        inventory = server.createInventory(
                this,
                45,
                messages.component(
                        player,
                        "ui.home-editor.title",
                        Placeholder.unparsed("home", home.name())
                )
        );
        inventory.setItem(ICON_SLOT, item(
                home.icon(),
                messages.component(
                        player,
                        "ui.home-editor.icon",
                        Placeholder.unparsed("icon", home.icon().getKey().asString())
                )
        ));
        inventory.setItem(DESCRIPTION_SLOT, item(
                Material.WRITABLE_BOOK,
                messages.component(player, "ui.home-editor.description")
        ));
        inventory.setItem(FAVORITE_SLOT, item(
                home.favorite() ? Material.NETHER_STAR : Material.GRAY_DYE,
                messages.component(
                        player,
                        home.favorite()
                                ? "ui.home-editor.favorite-on"
                                : "ui.home-editor.favorite-off"
                )
        ));
        inventory.setItem(CATEGORY_SLOT, item(
                Material.CHEST,
                messages.component(
                        player,
                        "ui.home-editor.category-label",
                        Placeholder.component(
                                "category",
                                messages.component(
                                        player,
                                        "ui.home-editor.category." + home.category().name().toLowerCase()
                                )
                        )
                )
        ));
        inventory.setItem(RENAME_SLOT, item(
                Material.NAME_TAG,
                messages.component(player, "ui.home-editor.rename")
        ));
        inventory.setItem(UPDATE_LOCATION_SLOT, item(
                Material.LODESTONE,
                messages.component(player, "ui.home-editor.update-location")
        ));
        inventory.setItem(ARRIVAL_STYLE_SLOT, item(
                Material.FIREWORK_ROCKET,
                messages.component(
                        player,
                        "ui.home-editor.arrival-style-label",
                        Placeholder.component(
                                "style",
                                messages.component(
                                        player,
                                        "ui.home-editor.arrival-style."
                                                + home.arrivalStyle().name().toLowerCase()
                                )
                        )
                )
        ));
        inventory.setItem(EARLIER_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.home-editor.earlier")
        ));
        inventory.setItem(LATER_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.home-editor.later")
        ));
        inventory.setItem(DELETE_SLOT, item(
                Material.LAVA_BUCKET,
                messages.component(player, "ui.home-editor.delete")
        ));
        inventory.setItem(BACK_SLOT, item(
                Material.BARRIER,
                messages.component(player, "ui.lock-panel.back")
        ));
    }

    UUID ownerId() {
        return ownerId;
    }

    String homeName() {
        return homeName;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private ItemStack item(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of());
        item.setItemMeta(meta);
        return item;
    }
}
