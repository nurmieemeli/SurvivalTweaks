package gg.nurmi.survivaltweaks.ui;

import org.bukkit.inventory.InventoryHolder;

/**
 * Marks an inventory owned by SurvivalTweaks. Every menu cancels its own clicks and drags, so
 * these views never hold items a player can take. Safety checks that must distinguish a plugin
 * menu from a real container — such as the teleport container guard — test for this interface.
 */
public interface SurvivalTweaksMenu extends InventoryHolder {
}
