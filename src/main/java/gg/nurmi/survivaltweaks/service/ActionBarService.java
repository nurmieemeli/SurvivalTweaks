package gg.nurmi.survivaltweaks.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ActionBarService {

    public static final int LOCK_HINT_PRIORITY = 10;
    public static final int SLEEP_PRIORITY = 30;
    public static final int TELEPORT_COOLDOWN_PRIORITY = 50;
    public static final int TELEPORT_PRIORITY = 100;
    private static final long CLIENT_REFRESH_MILLIS = 2_000L;

    private final Clock clock;
    private final Map<UUID, Entry> visible = new HashMap<>();

    public ActionBarService(Clock clock) {
        this.clock = clock;
    }

    public void show(Player player, Component component, int priority, Duration lifetime) {
        long now = clock.millis();
        Entry current = visible.get(player.getUniqueId());
        if (current != null && current.expiresAtMillis() > now && current.priority() > priority) {
            return;
        }
        long expiresAt = now + Math.max(0L, lifetime.toMillis());
        if (current != null
                && current.expiresAtMillis() > now
                && current.priority() == priority
                && current.component().equals(component)
                && current.refreshAtMillis() > now) {
            visible.put(
                    player.getUniqueId(),
                    new Entry(priority, component, expiresAt, current.refreshAtMillis())
            );
            return;
        }
        player.sendActionBar(component);
        visible.put(
                player.getUniqueId(),
                new Entry(priority, component, expiresAt, now + CLIENT_REFRESH_MILLIS)
        );
    }

    public void clear(Player player, int priority) {
        Entry current = visible.get(player.getUniqueId());
        if (current != null && current.priority() <= priority) {
            visible.remove(player.getUniqueId());
            player.sendActionBar(Component.empty());
        }
    }

    public void clearExact(Player player, int priority) {
        Entry current = visible.get(player.getUniqueId());
        if (current != null && current.priority() == priority) {
            visible.remove(player.getUniqueId());
            player.sendActionBar(Component.empty());
        }
    }

    public void forget(UUID playerId) {
        visible.remove(playerId);
    }

    private record Entry(
            int priority,
            Component component,
            long expiresAtMillis,
            long refreshAtMillis
    ) {
    }
}
