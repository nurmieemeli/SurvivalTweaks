package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class BlockRefillService implements Listener {

    private static final int FIRST_STORAGE_SLOT = 9;
    private static final int LAST_STORAGE_SLOT = 35;
    private static final int OFF_HAND_SLOT = 40;

    private final SurvivalTweaks plugin;
    private final SettingsService settings;

    public BlockRefillService(SurvivalTweaks plugin, SettingsService settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        PluginSettings current = settings.current();
        if (!current.hotbarRefillEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        EquipmentSlot slot = event.getHand();
        Material placedType = event.getItemInHand().getType();
        int targetSlot = slot == EquipmentSlot.OFF_HAND
                ? OFF_HAND_SLOT
                : player.getInventory().getHeldItemSlot();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            PlayerInventory inventory = player.getInventory();
            if (isEmpty(inventory.getItem(targetSlot))) {
                refillSlot(inventory, targetSlot, placedType);
            }
        }, 1L);
    }

    public boolean refillHand(Player player, EquipmentSlot slot, Material targetMaterial) {
        PlayerInventory inventory = player.getInventory();
        int targetSlot = slot == EquipmentSlot.OFF_HAND
                ? OFF_HAND_SLOT
                : inventory.getHeldItemSlot();
        return refillSlot(inventory, targetSlot, targetMaterial);
    }

    boolean refillSlot(PlayerInventory inventory, int targetSlot, Material targetMaterial) {
        for (int i = FIRST_STORAGE_SLOT; i <= LAST_STORAGE_SLOT; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && stack.getType() == targetMaterial && stack.getAmount() > 0) {
                ItemStack copy = stack.clone();
                inventory.setItem(i, null);
                inventory.setItem(targetSlot, copy);
                return true;
            }
        }
        return false;
    }

    private boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
