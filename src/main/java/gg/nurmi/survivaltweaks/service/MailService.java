package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MailService {

    private final Plugin plugin;
    private final Server server;
    private final SettingsService settings;
    private final ProfileRepository profiles;
    private final NotificationService notifications;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private final Map<UUID, Instant> cooldowns = new HashMap<>();
    private final Map<UUID, ArrayDeque<Instant>> hourlySends = new HashMap<>();
    private int sendsSinceCleanup;

    public MailService(
            Plugin plugin,
            Server server,
            SettingsService settings,
            ProfileRepository profiles,
            NotificationService notifications,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OfflinePlayer findRecipient(String name) {
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

    public CompletableFuture<SendResult> sendAsync(Player sender, OfflinePlayer recipient, String rawMessage) {
        if (!settings.current().mailEnabled()) {
            return CompletableFuture.completedFuture(SendResult.DISABLED);
        }
        if (recipient == null || (!recipient.isOnline() && !recipient.hasPlayedBefore())) {
            return CompletableFuture.completedFuture(SendResult.UNKNOWN_PLAYER);
        }
        if (sender.getUniqueId().equals(recipient.getUniqueId())) {
            return CompletableFuture.completedFuture(SendResult.SELF);
        }
        String message = normalize(rawMessage);
        if (message.isEmpty()) {
            return CompletableFuture.completedFuture(SendResult.EMPTY);
        }
        if (message.codePointCount(0, message.length()) > settings.current().mailMaximumLength()) {
            return CompletableFuture.completedFuture(SendResult.TOO_LONG);
        }
        
        return profiles.loadAsync(recipient.getUniqueId()).thenApplyAsync(recipientProfile -> {
            if (!recipientProfile.preferences().mailEnabled()
                    || recipientProfile.blocksMailFrom(sender.getUniqueId())) {
                return SendResult.UNAVAILABLE;
            }

            Instant now = clock.instant();
        if (++sendsSinceCleanup >= 64) {
            cleanupRateLimits(now);
            sendsSinceCleanup = 0;
        }
        Instant allowedAt = cooldowns.get(sender.getUniqueId());
        if (allowedAt != null && allowedAt.isAfter(now)) {
            return SendResult.COOLDOWN;
        }
        ArrayDeque<Instant> recent = hourlySends.computeIfAbsent(
                sender.getUniqueId(),
                ignored -> new ArrayDeque<>()
        );
        Instant cutoff = now.minusSeconds(3600);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
        if (recent.size() >= settings.current().mailMaximumPerHour()) {
            return SendResult.HOURLY_LIMIT;
        }

        notifications.mail(
                recipient.getUniqueId(),
                sender.getUniqueId(),
                sender.getName(),
                message
        );
        cooldowns.put(sender.getUniqueId(), now.plus(settings.current().mailCooldown()));
            recent.addLast(now);
            Player online = recipient.getPlayer();
            if (online != null) {
                messages.send(
                        online,
                        "mail.received",
                        Placeholder.unparsed("sender", sender.getName())
                );
                feedback.play(online, FeedbackService.MAIL);
            }
            return SendResult.SENT;
        }, runnable -> server.getScheduler().runTask(plugin, runnable));
    }

    private void cleanupRateLimits(Instant now) {
        cooldowns.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        Instant cutoff = now.minusSeconds(3600);
        hourlySends.values().forEach(recent -> {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
                recent.removeFirst();
            }
        });
        hourlySends.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void sendWithFeedbackAsync(Player sender, OfflinePlayer recipient, String rawMessage) {
        sendAsync(sender, recipient, rawMessage).thenAccept(result -> {
            if (!sender.isOnline()) {
                return;
            }
            String recipientName = recipient == null || recipient.getName() == null
                    ? ""
                    : recipient.getName();
            messages.send(
                    sender,
                    "mail.result." + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    Placeholder.unparsed("player", recipientName),
                    Placeholder.unparsed("maximum", Integer.toString(settings.current().mailMaximumLength()))
            );
            if (result == SendResult.SENT) {
                feedback.play(sender, FeedbackService.UI_CLICK);
            }
        });
    }

    public boolean block(Player owner, OfflinePlayer sender) {
        if (sender == null || owner.getUniqueId().equals(sender.getUniqueId())) {
            return false;
        }
        var profile = profiles.load(owner.getUniqueId());
        boolean changed = profile.blockMailFrom(sender.getUniqueId());
        if (changed) {
            profiles.save(profile);
        }
        return changed;
    }

    public boolean unblock(Player owner, OfflinePlayer sender) {
        if (sender == null) {
            return false;
        }
        var profile = profiles.load(owner.getUniqueId());
        boolean changed = profile.unblockMailFrom(sender.getUniqueId());
        if (changed) {
            profiles.save(profile);
        }
        return changed;
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public enum SendResult {
        SENT,
        DISABLED,
        UNKNOWN_PLAYER,
        SELF,
        EMPTY,
        TOO_LONG,
        UNAVAILABLE,
        COOLDOWN,
        HOURLY_LIMIT
    }
}
