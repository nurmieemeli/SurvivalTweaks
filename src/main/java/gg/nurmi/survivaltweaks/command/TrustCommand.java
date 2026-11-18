package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerNameLookup;
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
        boolean removing = command.getName().equalsIgnoreCase("untrust");
        if (arguments.length != 1) {
            messages.send(player, removing ? "untrust.usage" : "trust.usage");
            return true;
        }
        OfflinePlayer target = removing
                ? PlayerNameLookup.find(server, profiles, arguments[0])
                : PlayerNameLookup.findCached(server, arguments[0]);
        if (target == null) {
            messages.send(player, "trust.unknown-player");
            return true;
        }
        var targetId = target.getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            messages.send(player, removing ? "trust.cannot-untrust-self" : "trust.cannot-trust-self");
            return true;
        }

        String name = target.getName() == null ? arguments[0] : target.getName();
        Profile profile = profiles.load(player.getUniqueId());
        boolean changed = removing ? profile.untrustPlayer(targetId) : profile.trustPlayer(targetId);
        if (changed) {
            profiles.save(profile);
        }
        String reply = removing
                ? (changed ? "untrust.untrusted" : "untrust.not-trusted")
                : (changed ? "trust.trusted" : "trust.already-trusted");
        messages.send(player, reply, Placeholder.unparsed("player", name));
        return true;
    }
}
