package gg.nurmi.survivaltweaks.command.home;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class DeleteHomeCommand implements CommandExecutor, TabCompleter {

    private final ProfileRepository profiles;
    private final MessageService messages;
    private final FeedbackService feedback;

    public DeleteHomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.profiles = profiles;
        this.messages = messages;
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
        if (arguments.length > 1) {
            messages.send(player, "delete-home.usage");
            return true;
        }

        Profile profile = profiles.load(player.getUniqueId());
        List<Home> homes = profile.homes();
        if (homes.isEmpty()) {
            messages.send(player, "home.none");
            return true;
        }

        String name;
        if (arguments.length == 1) {
            name = arguments[0];
        } else if (homes.size() == 1) {
            name = homes.getFirst().name();
        } else {
            messages.send(player, "delete-home.name-required");
            return true;
        }

        if (!profile.removeHome(name)) {
            messages.send(player, "home.not-found", Placeholder.unparsed("home", name));
            return true;
        }

        profiles.save(profile);
        messages.send(player, "delete-home.deleted", Placeholder.unparsed("home", name));
        feedback.play(player, FeedbackService.HOME_DELETED);
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
