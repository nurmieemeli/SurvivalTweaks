package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.CustomDeathCause;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

public final class CustomDeathMessageService implements Listener {

    private final Server server;
    private final MessageService messages;
    private final SettingsService settings;
    private final RandomGenerator random;

    public CustomDeathMessageService(
            Server server,
            MessageService messages,
            SettingsService settings
    ) {
        this(server, messages, settings, RandomGenerator.getDefault());
    }

    CustomDeathMessageService(
            Server server,
            MessageService messages,
            SettingsService settings,
            RandomGenerator random
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.random = Objects.requireNonNull(random, "random");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        PluginSettings current = settings.current();
        if (!current.customDeathMessagesEnabled()
                || !event.getShowDeathMessages()
                || event.deathMessage() == null) {
            return;
        }

        Player player = event.getEntity();
        EntityDamageEvent damage = player.getLastDamageCause();
        if (damage == null || hasEntityAttribution(event.getDamageSource())) {
            return;
        }

        Optional<CustomDeathCause> customCause = CustomDeathCause.from(damage.getCause())
                .filter(cause -> current.customDeathMessageCauses().contains(cause.key()));
        if (customCause.isEmpty()) {
            return;
        }

        Component vanillaDeathMessage = event.deathMessage();
        if (event.deathScreenMessageOverride() == null) {
            event.deathScreenMessageOverride(vanillaDeathMessage);
        }
        event.setShowDeathMessages(false);

        CustomDeathCause cause = customCause.orElseThrow();
        boolean rare = random.nextInt(100) < current.customDeathMessageRareVariantPercent();
        String key = cause.messageKey(random.nextInt(2) + 1, rare);
        TagResolver playerName = Placeholder.component("player", Component.text(player.getName()));

        server.getOnlinePlayers().stream()
                .filter(viewer -> viewer.canSee(player))
                .forEach(viewer -> messages.send(viewer, key, playerName));
        messages.send(server.getConsoleSender(), key, playerName);
    }

    private boolean hasEntityAttribution(DamageSource source) {
        return source.getCausingEntity() != null || source.getDirectEntity() != null;
    }
}
