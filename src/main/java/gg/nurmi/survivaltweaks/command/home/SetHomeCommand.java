package gg.nurmi.survivaltweaks.command.home;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.OnboardingService;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class SetHomeCommand implements CommandExecutor {

    private static final Pattern VALID_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,32}");

    private final ProfileRepository profiles;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final OnboardingService onboarding;

    public SetHomeCommand(
            ProfileRepository profiles,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings,
            OnboardingService onboarding
    ) {
        this.profiles = profiles;
        this.messages = messages;
        this.feedback = feedback;
        this.settings = settings;
        this.onboarding = onboarding;
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
            messages.send(player, "set-home.usage");
            return true;
        }

        Profile profile = profiles.load(player.getUniqueId());
        int maxHomes = settings.current().maxHomes();
        if (profile.homes().size() >= maxHomes) {
            messages.send(player, "set-home.limit", Placeholder.unparsed("limit", Integer.toString(maxHomes)));
            return true;
        }

        String name;
        if (arguments.length == 1) {
            name = arguments[0];
        } else if (profile.homes().isEmpty()) {
            name = "home";
        } else {
            messages.send(player, "set-home.name-required");
            return true;
        }

        if (!VALID_NAME.matcher(name).matches()) {
            messages.send(player, "set-home.invalid-name");
            return true;
        }
        if (profile.home(name).isPresent()) {
            messages.send(player, "set-home.duplicate", Placeholder.unparsed("home", name));
            return true;
        }

        profile.addHome(Home.at(name, player.getLocation()));
        profiles.save(profile);
        messages.send(player, "set-home.saved", Placeholder.unparsed("home", name));
        feedback.play(player, FeedbackService.HOME_SAVED);
        onboarding.show(player, OnboardingHint.HOME);
        return true;
    }
}
