package gg.nurmi.survivaltweaks.command.home;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.ui.HomeMenuController;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final ProfileRepository profiles;
    private final MessageService messages;
    private final HomeMenuController homeMenu;

    public HomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            HomeMenuController homeMenu
    ) {
        this.profiles = profiles;
        this.messages = messages;
        this.homeMenu = homeMenu;
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
        if (arguments.length > 1) {
            messages.send(player, "home.usage");
            return true;
        }

        Profile profile = profiles.load(player.getUniqueId());
        if (arguments.length == 1) {
            Optional<Home> selected = profile.home(arguments[0]);
            if (selected.isEmpty()) {
                messages.send(player, "home.not-found", Placeholder.unparsed("home", arguments[0]));
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

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player) || arguments.length != 1) {
            return List.of();
        }
        String prefix = arguments[0].toLowerCase(Locale.ROOT);
        return profiles.load(player.getUniqueId()).homes().stream()
                .map(Home::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }
}
