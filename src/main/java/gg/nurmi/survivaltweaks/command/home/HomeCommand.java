package gg.nurmi.survivaltweaks.command.home;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerNameLookup;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.ui.HomeMenuController;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;

public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final ProfileRepository profiles;
    private final MessageService messages;
    private final HomeMenuController homeMenu;
    private final Plugin plugin;
    private final Server server;
    private final FeedbackService feedback;
    private final Map<UUID, Object> pendingHomeRequests = new HashMap<>();

    public HomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            HomeMenuController homeMenu,
            Plugin plugin,
            Server server,
            FeedbackService feedback
    ) {
        this.profiles = profiles;
        this.messages = messages;
        this.homeMenu = homeMenu;
        this.plugin = plugin;
        this.server = Objects.requireNonNull(server, "server");
        this.feedback = feedback;
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

        if (arguments.length == 3) {
            boolean invite = arguments[0].equalsIgnoreCase("invite");
            if (invite || arguments[0].equalsIgnoreCase("uninvite")) {
                handleSharing(player, arguments[1], arguments[2], invite);
                return true;
            }
        }

        if (arguments.length > 1) {
            messages.send(player, "home.usage");
            return true;
        }

        pendingHomeRequests.remove(player.getUniqueId());
        Profile profile = profiles.load(player.getUniqueId());
        if (arguments.length == 1) {
            String arg = arguments[0];
            if (arg.contains(":")) {
                String[] parts = arg.split(":", 2);
                handleSharedTeleport(player, parts[0], parts[1]);
                return true;
            }

            Optional<Home> selected = profile.home(arg);
            if (selected.isEmpty()) {
                messages.send(player, "home.not-found", Placeholder.unparsed("home", arg));
                return true;
            }
            homeMenu.teleport(player, selected.orElseThrow());
            return true;
        }

        List<Home> homes = profile.homes();
        if (homes.isEmpty()) {
            messages.send(player, "home.none");
        } else if (homes.size() == 1) {
            homeMenu.teleport(player, homes.getFirst());
        } else {
            if (!homeMenu.open(player, homes)) {
                String names = String.join(", ", homes.stream().map(Home::name).sorted().toList());
                messages.send(player, "home.list", Placeholder.unparsed("homes", names));
            }
        }
        return true;
    }

    private String displayName(OfflinePlayer player, String fallback) {
        String name = player.getName();
        return name == null ? fallback : name;
    }

    private void handleSharing(Player owner, String homeName, String targetName, boolean invite) {
        Profile ownerProfile = profiles.load(owner.getUniqueId());
        Optional<Home> homeOpt = ownerProfile.home(homeName);
        if (homeOpt.isEmpty()) {
            messages.send(owner, "home.not-found", Placeholder.unparsed("home", homeName));
            return;
        }

        OfflinePlayer target = PlayerNameLookup.find(server, profiles, targetName);
        if (target == null) {
            messages.send(owner, "teleport.player-not-found", Placeholder.unparsed("player", targetName));
            return;
        }

        Home home = homeOpt.orElseThrow();
        Home updated = invite ? home.invite(target.getUniqueId()) : home.uninvite(target.getUniqueId());
        ownerProfile.updateHome(updated);
        profiles.save(ownerProfile);

        messages.send(owner, invite ? "home.invited" : "home.uninvited",
                Placeholder.unparsed("player", displayName(target, targetName)), Placeholder.unparsed("home", homeName));
        if (invite && target.isOnline()) {
            messages.send(target.getPlayer(), "home.invite-received", Placeholder.unparsed("player", owner.getName()), Placeholder.unparsed("home", homeName));
        }
        if (feedback != null) {
            feedback.play(owner, FeedbackService.UI_CLICK);
        }
    }

    private void handleSharedTeleport(Player visitor, String ownerName, String homeName) {
        OfflinePlayer owner = PlayerNameLookup.find(server, profiles, ownerName);
        if (owner == null) {
            messages.send(visitor, "teleport.player-not-found", Placeholder.unparsed("player", ownerName));
            return;
        }

        UUID visitorId = visitor.getUniqueId();
        Object request = new Object();
        pendingHomeRequests.put(visitorId, request);
        Executor mainThread = task -> server.getScheduler().runTask(plugin, task);
        profiles.withProfileAsync(owner.getUniqueId(), mainThread,
                () -> pendingHomeRequests.get(visitorId) == request && server.getPlayer(visitorId) == visitor,
                profile -> {
                Optional<Home> homeOpt = profile.home(homeName);
                if (homeOpt.isEmpty()) {
                    messages.send(visitor, "home.not-found", Placeholder.unparsed("home", homeName));
                    return null;
                }

                Home home = homeOpt.orElseThrow();
                if (!home.isSharedWith(visitor.getUniqueId()) && !owner.getUniqueId().equals(visitor.getUniqueId())) {
                    messages.send(visitor, "home.not-shared");
                    return null;
                }

                homeMenu.teleport(visitor, home, () -> profile.home(homeName)
                        .filter(current -> current.isSharedWith(visitor.getUniqueId())
                                || owner.getUniqueId().equals(visitor.getUniqueId()))
                        .filter(current -> current.resolve(server).equals(home.resolve(server)))
                        .isPresent(), profiles.retain(profile));
                return null;
            }).whenCompleteAsync((ignored, failure) -> {
                if (pendingHomeRequests.remove(visitorId, request) && failure != null
                        && server.getPlayer(visitorId) == visitor) {
                    plugin.getLogger().log(Level.WARNING, "Could not load shared home", failure);
                    messages.send(visitor, "teleport.safety.unavailable");
                }
            }, mainThread);
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(profiles.load(player.getUniqueId()).matchingHomeNames(prefix));
            if ("invite".startsWith(prefix)) suggestions.add("invite");
            if ("uninvite".startsWith(prefix)) suggestions.add("uninvite");
            return suggestions;
        }

        if (arguments.length == 2 && (arguments[0].equalsIgnoreCase("invite") || arguments[0].equalsIgnoreCase("uninvite"))) {
            return profiles.load(player.getUniqueId()).matchingHomeNames(arguments[1]);
        }

        if (arguments.length == 3 && (arguments[0].equalsIgnoreCase("invite") || arguments[0].equalsIgnoreCase("uninvite"))) {
            return PlayerNameLookup.otherPlayerSuggestions(server, player, arguments[2], String.CASE_INSENSITIVE_ORDER);
        }

        return List.of();
    }
}
