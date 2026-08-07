package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TrustCommand implements CommandExecutor {

    private final Server server;
    private final ProfileRepository profiles;
    private final MessageService messages;

    public TrustCommand(Server server, ProfileRepository profiles, MessageService messages) {
        this.server = server;
        this.profiles = profiles;
        this.messages = messages;
    }

    private OfflinePlayer findTarget(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (arguments.length != 1) {
            messages.send(player, "trust.usage");
            return true;
        }
        OfflinePlayer target = findTarget(arguments[0]);
        if (target == null) {
            messages.send(player, "trust.unknown-player");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "trust.cannot-trust-self");
            return true;
        }

        String name = target.getName() == null ? arguments[0] : target.getName();
        Profile profile = profiles.load(player.getUniqueId());
        boolean changed = profile.trustPlayer(target.getUniqueId());
        if (changed) {
            profiles.save(profile);
        }
        messages.send(
                player,
                changed ? "trust.trusted" : "trust.already-trusted",
                Placeholder.unparsed("player", name)
        );
        return true;
    }
}
