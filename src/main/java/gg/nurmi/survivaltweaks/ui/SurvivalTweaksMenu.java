package gg.nurmi.survivaltweaks.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * Marks an inventory owned by SurvivalTweaks. Every menu cancels its own clicks and drags, so
 * these views never hold items a player can take. Safety checks that must distinguish a plugin
 * menu from a real container — such as the teleport container guard — test for this interface.
 */
public interface SurvivalTweaksMenu extends InventoryHolder {

    static void afterClick(Plugin plugin, InventoryClickEvent event, Player player, Runnable action) {
        Inventory inventory = event.getView().getTopInventory();
        if (event.getRawSlot() < 0 || event.getRawSlot() >= inventory.getSize()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().equals(inventory)) {
                action.run();
            }
        });
    }
}
