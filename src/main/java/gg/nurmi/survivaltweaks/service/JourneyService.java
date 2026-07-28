package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public final class JourneyService implements Listener {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final OnboardingService onboarding;

    public JourneyService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            OnboardingService onboarding
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.onboarding = onboarding;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.current().journeyEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) {
            onboarding.complete(player, OnboardingHint.WELCOME);
            return;
        }
        long delay = settings.current().journeyWelcomeDelayTicks();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> welcome(player), delay);
    }

    private void welcome(Player player) {
        if (!player.isOnline() || !settings.current().journeyEnabled()) {
            return;
        }
        boolean first = onboarding.show(player, OnboardingHint.WELCOME);
        onboarding.show(player, OnboardingHint.LANGUAGE);
        if (first && onboarding.guidanceEnabled(player.getUniqueId())) {
            player.showTitle(Title.title(
                    messages.component(player, "journey.title"),
                    messages.component(player, "journey.subtitle"),
                    Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(4),
                            Duration.ofSeconds(1)
                    )
            ));
        }
    }
}
