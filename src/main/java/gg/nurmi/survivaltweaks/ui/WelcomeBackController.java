package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.DeathRecoveryService;
import gg.nurmi.survivaltweaks.service.DisplayFormat;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class WelcomeBackController implements Listener, CommandExecutor {

    private static final int NOTIFICATIONS_SLOT = 10;
    private static final int MAIL_SLOT = 11;
    private static final int TELEPORTS_SLOT = 12;
    private static final int DEATH_SLOT = 14;
    private static final int NEWS_SLOT = 16;
    private static final int BACK_SLOT = 22;

    private final JavaPlugin plugin;
    private final Server server;
    private final SettingsService settings;
    private final ProfileRepository profiles;
    private final NotificationService notifications;
    private final TeleportRequestService teleports;
    private final DeathRecoveryService deathRecovery;
    private final NotificationCenterController notificationCenter;
    private final MailboxController mailbox;
    private final TeleportInboxController teleportInbox;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private final Map<UUID, Instant> previousSeen = new HashMap<>();
    private Consumer<Player> back = Player::closeInventory;

    public WelcomeBackController(
            JavaPlugin plugin,
            SettingsService settings,
            ProfileRepository profiles,
            NotificationService notifications,
            TeleportRequestService teleports,
            DeathRecoveryService deathRecovery,
            NotificationCenterController notificationCenter,
            MailboxController mailbox,
            TeleportInboxController teleportInbox,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.settings = settings;
        this.profiles = profiles;
        this.notifications = notifications;
        this.teleports = teleports;
        this.deathRecovery = deathRecovery;
        this.notificationCenter = notificationCenter;
        this.mailbox = mailbox;
        this.teleportInbox = teleportInbox;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void playerJoined(Player player) {
        var profile = profiles.load(player.getUniqueId());
        Instant lastSeen = profile.lastSeenAt().orElse(null);
        profile.lastKnownName(player.getName());
        profile.playTimeTicks(player.getStatistic(Statistic.PLAY_ONE_MINUTE));
        profiles.save(profile);
        if (lastSeen != null) {
            previousSeen.put(player.getUniqueId(), lastSeen);
        }
        if (!settings.current().welcomeBackEnabled()
                || !shouldShow(
                player.hasPlayedBefore(),
                lastSeen,
                clock.instant(),
                settings.current().welcomeBackMinimumAway()
        )) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> present(player),
                settings.current().welcomeBackDelayTicks()
        );
    }

    public void playerLeaving(Player player) {
        previousSeen.remove(player.getUniqueId());
        var profile = profiles.load(player.getUniqueId());
        profile.lastKnownName(player.getName());
        profile.lastSeenAt(clock.instant());
        profile.playTimeTicks(player.getStatistic(Statistic.PLAY_ONE_MINUTE));
        profiles.save(profile);
    }

    private void present(Player player) {
        if (!player.isOnline() || !settings.current().welcomeBackEnabled()) {
            return;
        }
        if (settings.current().welcomeBackAutoOpen()) {
            open(player);
            return;
        }
        Component prompt = messages.component(
                player,
                "welcome-back.prompt",
                Placeholder.component(
                        "away",
                        DisplayFormat.away(messages, player, awayDuration(player.getUniqueId()))
                ),
                Placeholder.unparsed(
                        "unread",
                        Long.toString(notifications.unread(player.getUniqueId()))
                )
        ).clickEvent(ClickEvent.runCommand("/welcome"))
                .hoverEvent(HoverEvent.showText(messages.component(
                        player,
                        "welcome-back.prompt-hover"
                )));
        player.sendMessage(prompt);
        feedback.play(player, FeedbackService.MAIL);
    }

    public void open(Player player) {
        var profile = profiles.load(player.getUniqueId());
        long unread = profile.unreadNotificationCount();
        long unreadMail = profile.unreadMailCount();
        int pending = teleports.incomingRequests(player.getUniqueId(), clock.instant()).size();
        boolean death = deathRecovery.hasActiveMarker(player.getUniqueId());
        WelcomeHolder holder = new WelcomeHolder(
                player.getUniqueId(),
                server,
                messages.component(player, "ui.welcome-back.title")
        );
        Inventory inventory = holder.getInventory();
        inventory.setItem(NOTIFICATIONS_SLOT, item(
                unread > 0 ? Material.BELL : Material.PAPER,
                messages.component(player, "ui.welcome-back.notifications"),
                List.of(messages.component(
                        player,
                        "ui.welcome-back.count",
                        Placeholder.unparsed("count", Long.toString(unread))
                ))
        ));
        inventory.setItem(MAIL_SLOT, item(
                unreadMail > 0 ? Material.WRITABLE_BOOK : Material.BOOK,
                messages.component(player, "ui.welcome-back.mail"),
                List.of(messages.component(
                        player,
                        "ui.welcome-back.count",
                        Placeholder.unparsed("count", Long.toString(unreadMail))
                ))
        ));
        inventory.setItem(TELEPORTS_SLOT, item(
                Material.ENDER_PEARL,
                messages.component(player, "ui.welcome-back.teleports"),
                List.of(messages.component(
                        player,
                        "ui.welcome-back.count",
                        Placeholder.unparsed("count", Integer.toString(pending))
                ))
        ));
        inventory.setItem(DEATH_SLOT, item(
                death ? Material.RECOVERY_COMPASS : Material.COMPASS,
                messages.component(player, "ui.welcome-back.death"),
                List.of(messages.component(
                        player,
                        death
                                ? "ui.welcome-back.death-active"
                                : "ui.welcome-back.death-none"
                ))
        ));
        inventory.setItem(NEWS_SLOT, item(
                Material.WRITTEN_BOOK,
                messages.component(player, "ui.welcome-back.news"),
                List.of(messages.component(player, "ui.welcome-back.news-detail"))
        ));
        inventory.setItem(BACK_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of(messages.component(
                        player,
                        "ui.welcome-back.away",
                        Placeholder.component(
                                "away",
                                DisplayFormat.away(messages, player, awayDuration(player.getUniqueId()))
                        )
                ))
        ));
        player.openInventory(inventory);
        feedback.play(player, FeedbackService.UI_OPEN);
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
        if (arguments.length != 0) {
            messages.send(player, "welcome-back.usage");
            return true;
        }
        open(player);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof WelcomeHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        switch (event.getRawSlot()) {
            case NOTIFICATIONS_SLOT -> notificationCenter.open(player);
            case MAIL_SLOT -> mailbox.open(player);
            case TELEPORTS_SLOT -> teleportInbox.open(player);
            case DEATH_SLOT -> {
                player.closeInventory();
                deathRecovery.openStatus(player);
            }
            case BACK_SLOT -> back.accept(player);
            default -> {
                return;
            }
        }
        feedback.play(player, FeedbackService.UI_CLICK);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof WelcomeHolder) {
            event.setCancelled(true);
        }
    }

    static boolean shouldShow(
            boolean hasPlayedBefore,
            Instant lastSeen,
            Instant now,
            Duration minimumAway
    ) {
        return hasPlayedBefore
                && lastSeen != null
                && !lastSeen.isAfter(now)
                && !Duration.between(lastSeen, now).minus(minimumAway).isNegative();
    }

    private Duration awayDuration(UUID playerId) {
        Instant lastSeen = previousSeen.get(playerId);
        if (lastSeen == null) {
            lastSeen = profiles.load(playerId).lastSeenAt().orElse(clock.instant());
        }
        return Duration.between(lastSeen, clock.instant());
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class WelcomeHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Inventory inventory;

        private WelcomeHolder(UUID ownerId, Server server, Component title) {
            this.ownerId = ownerId;
            this.inventory = server.createInventory(this, 27, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
