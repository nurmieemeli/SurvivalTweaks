package gg.nurmi.survivaltweaks.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks requests shared by the profile and statistics menus on the server thread. */
public final class ProfileMenuRequests implements Listener {
    private final Map<UUID, Request> pending = new HashMap<>();

    Request begin(Player viewer) {
        Request request = new Request(viewer);
        pending.put(viewer.getUniqueId(), request);
        return request;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    final class Request {
        private final Player viewer;
        private final Inventory origin;

        private Request(Player viewer) {
            this.viewer = viewer;
            this.origin = viewer.getOpenInventory().getTopInventory();
        }

        boolean isCurrent() {
            return pending.get(viewer.getUniqueId()) == this && viewer.isOnline()
                    && origin.equals(viewer.getOpenInventory().getTopInventory());
        }

        void finish() {
            pending.remove(viewer.getUniqueId(), this);
        }
    }
}
