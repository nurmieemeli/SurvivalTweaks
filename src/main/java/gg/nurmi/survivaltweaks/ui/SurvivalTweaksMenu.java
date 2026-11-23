package gg.nurmi.survivaltweaks.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Marks an inventory owned by SurvivalTweaks. Every menu cancels its own clicks and drags, so
 * these views never hold items a player can take. Safety checks that must distinguish a plugin
 * menu from a real container — such as the teleport container guard — test for this interface.
 */
public interface SurvivalTweaksMenu extends InventoryHolder {

    static void afterClick(
            Plugin plugin, InventoryClickEvent event, UUID viewerId, BiConsumer<Player, Integer> action
    ) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(viewerId)) {
            return;
        }
        Inventory inventory = event.getView().getTopInventory();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().equals(inventory)) {
                action.accept(player, slot);
            }
        });
    }
}
