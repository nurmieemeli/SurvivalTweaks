package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MentionService implements Listener {

    private static final Pattern MENTION = Pattern.compile(
            "(?<![A-Za-z0-9_])@([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])"
    );

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final PlayerExperienceService experience;
    private final PlayerListService playerList;
    private final Clock clock;
    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    private final Map<UUID, HighlightPattern> highlightPatterns = new ConcurrentHashMap<>();
    private final Map<String, UUID> onlineByName = new ConcurrentHashMap<>();
    private final Map<MentionKey, Instant> cooldowns = new HashMap<>();

    public MentionService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            PlayerExperienceService experience,
            PlayerListService playerList,
            Clock clock
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.feedback = feedback;
        this.experience = experience;
        this.playerList = playerList;
        this.clock = clock;
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            onlineByName.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
            preferenceChanged(player);
        });
    }

    public void process(Player sender, Component message) {
        if (!settings.current().mentionsEnabled()) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        java.util.List<String> names = mentionedNames(
                plain,
                settings.current().mentionMaxPerMessage()
        );
        if (!names.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> notifyTargets(sender, names));
        }
    }

    public Component highlight(Audience audience, Component message) {
        if (!settings.current().mentionsEnabled()
                || !(audience instanceof Player player)
                || !enabled.getOrDefault(player.getUniqueId(), true)) {
            return message;
        }
        HighlightPattern personal = highlightPatterns.compute(
                player.getUniqueId(),
                (ignored, cached) -> cached != null && cached.playerName().equals(player.getName())
                        ? cached
                        : HighlightPattern.forPlayer(player.getName())
        );
        return message.replaceText(TextReplacementConfig.builder()
                .match(personal.pattern())
                .replacement(personal.replacement())
                .build());
    }

    public void preferenceChanged(Player player) {
        enabled.put(
                player.getUniqueId(),
                experience.preferences(player).mentionNotificationsEnabled()
        );
    }

    static java.util.List<String> mentionedNames(String message, int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        ArrayList<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        Matcher matcher = MENTION.matcher(message);
        while (matcher.find() && names.size() < maximum) {
            String name = matcher.group(1);
            if (unique.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        return java.util.List.copyOf(names);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        onlineByName.put(
                event.getPlayer().getName().toLowerCase(Locale.ROOT),
                event.getPlayer().getUniqueId()
        );
        preferenceChanged(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        enabled.remove(playerId);
        highlightPatterns.remove(playerId);
        onlineByName.remove(event.getPlayer().getName().toLowerCase(Locale.ROOT), playerId);
        cooldowns.keySet().removeIf(key ->
                key.senderId().equals(playerId) || key.targetId().equals(playerId));
    }

    private void notifyTargets(Player sender, java.util.List<String> names) {
        if (!sender.isOnline()) {
            return;
        }
        for (String name : names) {
            UUID targetId = onlineByName.get(name.toLowerCase(Locale.ROOT));
            Player target = targetId == null ? null : plugin.getServer().getPlayer(targetId);
            if (target == null
                    || target.getUniqueId().equals(sender.getUniqueId())
                    || !sender.canSee(target)
                    || !enabled.getOrDefault(target.getUniqueId(), true)) {
                continue;
            }
            MentionKey key = new MentionKey(sender.getUniqueId(), target.getUniqueId());
            Instant allowedAt = cooldowns.get(key);
            Instant now = clock.instant();
            if (allowedAt != null && allowedAt.isAfter(now)) {
                continue;
            }
            cooldowns.put(key, now.plus(settings.current().mentionCooldown()));
            feedback.play(target, FeedbackService.MENTION);
            if (playerList.isAfk(target.getUniqueId())) {
                messages.send(
                        sender,
                        "mentions.target-afk",
                        Placeholder.unparsed("player", target.getName())
                );
            }
        }
    }

    private record MentionKey(UUID senderId, UUID targetId) {
    }

    private record HighlightPattern(String playerName, Pattern pattern, Component replacement) {

        private static HighlightPattern forPlayer(String playerName) {
            return new HighlightPattern(
                    playerName,
                    Pattern.compile(
                            "(?i)(?<![A-Za-z0-9_])@" + Pattern.quote(playerName) + "(?![A-Za-z0-9_])"
                    ),
                    Component.text(
                            "@" + playerName,
                            NamedTextColor.YELLOW,
                            TextDecoration.BOLD
                    )
            );
        }
    }
}
