package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.DisplayFormat;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerListService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class SocialProfileController implements Listener, CommandExecutor, TabCompleter {

    public static final String BYPASS_PERMISSION = "survivaltweaks.profile.bypass";
    private static final int STATISTICS_SLOT = 15;
    private static final int MAIL_SLOT = 20;
    private static final int TELEPORT_SLOT = 21;
    private static final int BACK_SLOT = 22;

    private final Server server;
    private final ProfileRepository profiles;
    private final SettingsService settings;
    private final PlayerListService playerList;
    private final MailboxController mailbox;
    private final StatisticsJournalController statistics;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private Consumer<Player> back = Player::closeInventory;

    public SocialProfileController(
            Server server,
            ProfileRepository profiles,
            SettingsService settings,
            PlayerListService playerList,
            MailboxController mailbox,
            StatisticsJournalController statistics,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.server = server;
        this.profiles = profiles;
        this.settings = settings;
        this.playerList = playerList;
        this.mailbox = mailbox;
        this.statistics = statistics;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public boolean open(Player viewer, OfflinePlayer target) {
        if (!settings.current().playerProfilesEnabled()) {
            messages.send(viewer, "profile.disabled");
            return false;
        }
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            messages.send(viewer, "profile.unknown");
            return false;
        }
        var targetProfile = profiles.load(target.getUniqueId());
        boolean own = viewer.getUniqueId().equals(target.getUniqueId());
        if (!own
                && !targetProfile.preferences().publicProfileEnabled()
                && !viewer.hasPermission(BYPASS_PERMISSION)) {
            messages.send(viewer, "profile.private");
            return false;
        }
        String targetName = target.getName() == null
                ? targetProfile.lastKnownName()
                : target.getName();
        if (targetName.isBlank()) {
            targetName = target.getUniqueId().toString().substring(0, 8);
        }
        ProfileHolder holder = new ProfileHolder(
                viewer.getUniqueId(),
                target.getUniqueId(),
                server,
                messages.component(
                        viewer,
                        "ui.profile.title",
                        Placeholder.unparsed("player", targetName)
                )
        );
        Inventory inventory = holder.getInventory();
        inventory.setItem(10, head(
                target,
                messages.component(
                        viewer,
                        "ui.profile.player",
                        Placeholder.unparsed("player", targetName)
                ),
                List.of(messages.component(
                        viewer,
                        "ui.profile.uuid",
                        Placeholder.unparsed("uuid", target.getUniqueId().toString())
                ))
        ));

        Player online = target.getPlayer();
        boolean visibleOnline = online != null && (own || viewer.canSee(online));
        String status = visibleOnline
                ? (playerList.isAfk(target.getUniqueId()) ? "afk" : "online")
                : "offline";
        inventory.setItem(12, item(
                visibleOnline ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.component(viewer, "ui.profile.status." + status),
                visibleOnline
                        ? List.of(messages.component(
                        viewer,
                        "ui.profile.world",
                        Placeholder.unparsed("world", online.getWorld().getName())
                ))
                        : List.of(lastSeen(viewer, targetProfile.lastSeenAt().orElse(null)))
        ));
        long playTicks = targetProfile.playTimeTicks();
        if (online != null) {
            playTicks = Math.max(0, online.getStatistic(Statistic.PLAY_ONE_MINUTE));
        }
        inventory.setItem(13, item(
                Material.CLOCK,
                messages.component(viewer, "ui.profile.playtime"),
                List.of(messages.component(
                        viewer,
                        "ui.profile.playtime-value",
                        Placeholder.component(
                                "time",
                                DisplayFormat.hoursMinutes(messages, viewer, playTicks)
                        )
                ))
        ));
        long firstPlayed = target.getFirstPlayed();
        long joinedDaysAgo = firstPlayed <= 0
                ? 0
                : Math.max(0, Duration.between(
                Instant.ofEpochMilli(firstPlayed),
                clock.instant()
        ).toDays());
        inventory.setItem(14, item(
                Material.OAK_SAPLING,
                messages.component(viewer, "ui.profile.first-joined"),
                List.of(messages.component(
                        viewer,
                        "ui.profile.first-joined-value",
                        Placeholder.unparsed("days", Long.toString(joinedDaysAgo))
                ))
        ));
        if (settings.current().statisticsEnabled()) {
            inventory.setItem(STATISTICS_SLOT, item(
                    Material.WRITABLE_BOOK,
                    messages.component(viewer, "ui.profile.statistics"),
                    List.of(messages.component(viewer, "ui.profile.statistics-description"))
            ));
        }
        inventory.setItem(16, item(
                Material.RED_BED,
                messages.component(viewer, "ui.profile.homes"),
                List.of(messages.component(
                        viewer,
                        MessageService.plural("ui.profile.homes-value", targetProfile.homes().size()),
                        Placeholder.unparsed("count", Integer.toString(targetProfile.homes().size()))
                ))
        ));
        if (!own) {
            inventory.setItem(MAIL_SLOT, item(
                    Material.WRITABLE_BOOK,
                    messages.component(viewer, "ui.profile.send-mail"),
                    List.of(messages.component(viewer, "ui.profile.send-mail-description"))
            ));
            if (visibleOnline) {
                inventory.setItem(TELEPORT_SLOT, item(
                        Material.ENDER_PEARL,
                        messages.component(viewer, "ui.profile.teleport"),
                        List.of(messages.component(viewer, "ui.profile.teleport-description"))
                ));
            }
        }
        inventory.setItem(BACK_SLOT, item(
                Material.ARROW,
                messages.component(viewer, "ui.common.back"),
                List.of()
        ));
        viewer.openInventory(inventory);
        feedback.play(viewer, FeedbackService.UI_OPEN);
        return true;
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
            messages.send(player, "profile.usage");
            return true;
        }
        OfflinePlayer target = arguments.length == 0
                ? player
                : find(arguments[0]);
        open(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments
    ) {
        if (arguments.length != 1) {
            return List.of();
        }
        String prefix = arguments[0].toLowerCase(Locale.ROOT);
        return server.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ProfileHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)
                || !viewer.getUniqueId().equals(holder.viewerId())) {
            return;
        }
        if (event.getRawSlot() == BACK_SLOT) {
            back.accept(viewer);
        } else if (event.getRawSlot() == STATISTICS_SLOT) {
            OfflinePlayer target = server.getOfflinePlayer(holder.targetId());
            statistics.open(
                    viewer,
                    target,
                    player -> open(player, server.getOfflinePlayer(holder.targetId()))
            );
        } else if (event.getRawSlot() == MAIL_SLOT
                && !viewer.getUniqueId().equals(holder.targetId())) {
            mailbox.compose(viewer, server.getOfflinePlayer(holder.targetId()));
        } else if (event.getRawSlot() == TELEPORT_SLOT) {
            Player target = server.getPlayer(holder.targetId());
            if (target != null && viewer.canSee(target)) {
                viewer.closeInventory();
                viewer.performCommand("teleport " + target.getName());
            }
        } else {
            return;
        }
        feedback.play(viewer, FeedbackService.UI_CLICK);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ProfileHolder) {
            event.setCancelled(true);
        }
    }

    private OfflinePlayer find(String name) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    private Component lastSeen(Player viewer, Instant lastSeen) {
        if (lastSeen == null) {
            return messages.component(viewer, "ui.profile.last-seen-unknown");
        }
        long minutes = Math.max(0, Duration.between(lastSeen, clock.instant()).toMinutes());
        return messages.component(
                viewer,
                MessageService.plural("ui.profile.last-seen", minutes),
                Placeholder.unparsed("minutes", Long.toString(minutes))
        );
    }

    private ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = item(Material.PLAYER_HEAD, name, lore);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class ProfileHolder implements SurvivalTweaksMenu {

        private final UUID viewerId;
        private final UUID targetId;
        private final Inventory inventory;

        private ProfileHolder(
                UUID viewerId,
                UUID targetId,
                Server server,
                Component title
        ) {
            this.viewerId = viewerId;
            this.targetId = targetId;
            this.inventory = server.createInventory(this, 27, title);
        }

        private UUID viewerId() {
            return viewerId;
        }

        private UUID targetId() {
            return targetId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
