package gg.nurmi.survivaltweaks.command.home;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final ProfileRepository profiles;
    private final MessageService messages;
    private final HomeMenuController homeMenu;
    private final Server server;
    private final FeedbackService feedback;

    public HomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            HomeMenuController homeMenu
    ) {
        this(profiles, messages, homeMenu, null, null);
    }

    public HomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            HomeMenuController homeMenu,
            Server server,
            FeedbackService feedback
    ) {
        this.profiles = profiles;
        this.messages = messages;
        this.homeMenu = homeMenu;
        this.server = server;
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

        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("invite")) {
            handleInvite(player, arguments[1], arguments[2]);
            return true;
        }
        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("uninvite")) {
            handleUninvite(player, arguments[1], arguments[2]);
            return true;
        }

        if (arguments.length > 1) {
            messages.send(player, "home.usage");
            return true;
        }

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

    private void handleInvite(Player owner, String homeName, String targetName) {
        Profile ownerProfile = profiles.load(owner.getUniqueId());
        Optional<Home> homeOpt = ownerProfile.home(homeName);
        if (homeOpt.isEmpty()) {
            messages.send(owner, "home.not-found", Placeholder.unparsed("home", homeName));
            return;
        }

        OfflinePlayer target = server != null ? server.getOfflinePlayer(targetName) : null;
        if (target == null || target.getName() == null) {
            messages.send(owner, "teleport.player-not-found", Placeholder.unparsed("player", targetName));
            return;
        }

        Home updated = homeOpt.orElseThrow().invite(target.getUniqueId());
        ownerProfile.addHome(updated);
        profiles.save(ownerProfile);

        messages.send(owner, "home.invited", Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("home", homeName));
        if (target.isOnline()) {
            messages.send(target.getPlayer(), "home.invite-received", Placeholder.unparsed("player", owner.getName()), Placeholder.unparsed("home", homeName));
        }
        if (feedback != null) {
            feedback.play(owner, FeedbackService.UI_CLICK);
        }
    }

    private void handleUninvite(Player owner, String homeName, String targetName) {
        Profile ownerProfile = profiles.load(owner.getUniqueId());
        Optional<Home> homeOpt = ownerProfile.home(homeName);
        if (homeOpt.isEmpty()) {
            messages.send(owner, "home.not-found", Placeholder.unparsed("home", homeName));
            return;
        }

        OfflinePlayer target = server != null ? server.getOfflinePlayer(targetName) : null;
        if (target == null || target.getName() == null) {
            messages.send(owner, "teleport.player-not-found", Placeholder.unparsed("player", targetName));
            return;
        }

        Home updated = homeOpt.orElseThrow().uninvite(target.getUniqueId());
        ownerProfile.addHome(updated);
        profiles.save(ownerProfile);

        messages.send(owner, "home.uninvited", Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("home", homeName));
        if (feedback != null) {
            feedback.play(owner, FeedbackService.UI_CLICK);
        }
    }

    private void handleSharedTeleport(Player visitor, String ownerName, String homeName) {
        if (server == null) {
            messages.send(visitor, "home.not-found", Placeholder.unparsed("home", homeName));
            return;
        }

        OfflinePlayer owner = server.getOfflinePlayer(ownerName);
        if (owner == null || owner.getUniqueId() == null) {
            messages.send(visitor, "teleport.player-not-found", Placeholder.unparsed("player", ownerName));
            return;
        }

        Profile ownerProfile = profiles.load(owner.getUniqueId());
        Optional<Home> homeOpt = ownerProfile.home(homeName);
        if (homeOpt.isEmpty()) {
            messages.send(visitor, "home.not-found", Placeholder.unparsed("home", homeName));
            return;
        }

        Home home = homeOpt.orElseThrow();
        if (!home.isSharedWith(visitor.getUniqueId()) && !owner.getUniqueId().equals(visitor.getUniqueId())) {
            messages.send(visitor, "home.not-shared");
            return;
        }

        homeMenu.teleport(visitor, home);
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
            List<String> suggestions = new ArrayList<>(profiles.load(player.getUniqueId()).homes().stream()
                    .map(Home::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList());
            if ("invite".startsWith(prefix)) suggestions.add("invite");
            if ("uninvite".startsWith(prefix)) suggestions.add("uninvite");
            return suggestions;
        }

        if (arguments.length == 2 && (arguments[0].equalsIgnoreCase("invite") || arguments[0].equalsIgnoreCase("uninvite"))) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return profiles.load(player.getUniqueId()).homes().stream()
                    .map(Home::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        if (arguments.length == 3 && (arguments[0].equalsIgnoreCase("invite") || arguments[0].equalsIgnoreCase("uninvite")) && server != null) {
            String prefix = arguments[2].toLowerCase(Locale.ROOT);
            return server.getOnlinePlayers().stream()
                    .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        return List.of();
    }
}
