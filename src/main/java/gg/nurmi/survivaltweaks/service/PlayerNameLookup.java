package gg.nurmi.survivaltweaks.service;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

public final class PlayerNameLookup {
    private PlayerNameLookup() {
    }

    /** Uses only local records. Paper's lookup by name can contact Mojang and invent unknown profiles. */
    public static OfflinePlayer find(Server server, ProfileRepository profiles, String name) {
        return find(server, name, missing -> {
            var savedId = profiles.playerIdByName(missing);
            return savedId == null ? null : server.getOfflinePlayer(savedId);
        });
    }

    public static OfflinePlayer findCached(Server server, String name) {
        return find(server, name, ignored -> null);
    }

    public static String displayName(Server server, UUID playerId) {
        return displayName(playerId, server.getOfflinePlayer(playerId).getName());
    }

    public static List<String> suggestions(Server server, CommandSender sender, String input) {
        return suggestions(server.getOnlinePlayers().stream(), sender, input, String.CASE_INSENSITIVE_ORDER);
    }

    public static List<String> otherPlayerSuggestions(
            Server server, CommandSender sender, String input, Comparator<String> order
    ) {
        var candidates = server.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer)
                        || !player.getUniqueId().equals(viewer.getUniqueId()));
        return suggestions(candidates, sender, input, order);
    }

    private static List<String> suggestions(
            Stream<? extends Player> candidates, CommandSender sender, String input, Comparator<String> order
    ) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(order)
                .toList();
    }

    static String displayName(UUID playerId, String name) {
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    private static OfflinePlayer find(Server server, String name, Function<String, OfflinePlayer> onCacheMiss) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached.hasPlayedBefore() ? cached : null;
        }
        return onCacheMiss.apply(name);
    }
}
