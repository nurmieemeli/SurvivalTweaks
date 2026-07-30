package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PlayerListService implements Listener, AutoCloseable {

    private static final Component SEPARATOR = Component.text(" • ", NamedTextColor.DARK_GRAY);
    private static final long WORLD_TIME_STEP_MINUTES = 10L;

    private final JavaPlugin plugin;
    private final Server server;
    private final MessageService messages;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final PlayerExperienceService experience;
    private final FeedbackService feedback;
    private final AfkTracker afk;
    private final TaskFailureIsolation failures;
    private final Map<UUID, RowState> rowStates = new HashMap<>();
    private final Map<UUID, ViewState> viewStates = new HashMap<>();
    private BukkitTask refreshTask;
    private boolean rowsDirty = true;

    public PlayerListService(
            JavaPlugin plugin,
            MessageService messages,
            SettingsService settings,
            NotificationService notifications,
            PlayerExperienceService experience,
            FeedbackService feedback,
            Clock clock
    ) {
        this(plugin, messages, settings, notifications, experience, feedback, clock, null);
    }

    public PlayerListService(
            JavaPlugin plugin,
            MessageService messages,
            SettingsService settings,
            NotificationService notifications,
            PlayerExperienceService experience,
            FeedbackService feedback,
            Clock clock,
            TaskFailureIsolation failures
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.experience = Objects.requireNonNull(experience, "experience");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.afk = new AfkTracker(Objects.requireNonNull(clock, "clock"));
        this.failures = failures;
    }

    public void start() {
        server.getOnlinePlayers().forEach(player -> afk.joined(player.getUniqueId()));
        reconfigure();
    }

    public void reconfigure() {
        cancelRefreshTask();
        rowStates.clear();
        viewStates.clear();
        rowsDirty = true;
        if (!settings.current().playerListEnabled()) {
            clearPresentation();
            return;
        }
        refresh();
        long interval = settings.current().playerListRefreshSeconds() * 20L;
        Runnable refresh = this::refresh;
        refreshTask = server.getScheduler().runTaskTimer(
                plugin,
                failures == null ? refresh : failures.guard("player list refresh", refresh),
                interval,
                interval
        );
    }

    public AfkTracker.State toggleAfk(Player player) {
        if (!settings.current().playerListEnabled()
                || !settings.current().afkIndicatorsEnabled()) {
            return null;
        }
        AfkTracker.State state = afk.toggle(player.getUniqueId());
        showAfkTransition(player, state);
        rowsDirty = true;
        refresh();
        return state;
    }

    public boolean isAfk(UUID playerId) {
        return afk.isAfk(playerId);
    }

    public void preferenceChanged(Player player) {
        if (!settings.current().playerListEnabled()) {
            return;
        }
        viewStates.remove(player.getUniqueId());
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        afk.joined(event.getPlayer().getUniqueId());
        rowsDirty = true;
        server.getScheduler().runTask(plugin, this::refresh);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        afk.left(event.getPlayer().getUniqueId());
        rowStates.remove(event.getPlayer().getUniqueId());
        viewStates.remove(event.getPlayer().getUniqueId());
        rowsDirty = true;
        server.getScheduler().runTask(plugin, this::refresh);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasExplicitlyChangedPosition() || event.hasChangedOrientation()) {
            if (afk.movementActivity(event.getPlayer().getUniqueId())) {
                showAfkTransition(event.getPlayer(), AfkTracker.State.ACTIVE);
                rowsDirty = true;
                refresh();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            activity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            activity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isAfkCommand(event.getMessage())) {
            return;
        }
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        activity(event.getPlayer());
        refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (afk.activity(event.getPlayer().getUniqueId())) {
            server.getScheduler().runTask(plugin, () -> {
                showAfkTransition(event.getPlayer(), AfkTracker.State.ACTIVE);
                rowsDirty = true;
                refresh();
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            activity(damager);
        }
        if (event.getEntity() instanceof Player victim) {
            activity(victim);
        }
    }

    public void refresh() {
        PluginSettings current = settings.current();
        if (!current.playerListEnabled()) {
            clearPresentation();
            return;
        }
        ArrayList<Player> players = new ArrayList<>(server.getOnlinePlayers());
        ArrayList<UUID> online = new ArrayList<>(players.size());
        players.forEach(player -> online.add(player.getUniqueId()));
        Set<UUID> newlyAway = current.afkIndicatorsEnabled()
                ? afk.newlyAutomaticAfk(online, current.afkTimeout())
                : Set.of();
        if (!newlyAway.isEmpty()) {
            newlyAway.forEach(playerId -> {
                Player player = server.getPlayer(playerId);
                if (player != null) {
                    showAfkTransition(player, AfkTracker.State.AFK);
                }
            });
            rowsDirty = true;
        }
        int away = 0;
        if (current.afkIndicatorsEnabled()) {
            for (UUID playerId : online) {
                if (afk.isAfk(playerId)) {
                    away++;
                }
            }
        }
        RefreshContext context = new RefreshContext(
                current,
                players,
                players.size(),
                players.size() - away,
                away,
                server.getMaxPlayers(),
                greetingKey(),
                quantizeTenths(oneMinuteTps()),
                quantizeTenths(finiteNonNegative(server.getAverageTickTime()))
        );
        if (rowsDirty) {
            refreshRows(context);
            rowsDirty = false;
        }
        players.forEach(player -> refreshPlayer(player, context));
    }

    private void activity(Player player) {
        if (afk.activity(player.getUniqueId())) {
            showAfkTransition(player, AfkTracker.State.ACTIVE);
            rowsDirty = true;
            refresh();
        }
    }

    private void showAfkTransition(Player player, AfkTracker.State state) {
        boolean away = state == AfkTracker.State.AFK;
        messages.send(player, away ? "afk.enabled" : "afk.disabled");
        feedback.play(player, away ? FeedbackService.AFK_ENABLED : FeedbackService.AFK_DISABLED);
    }

    private void refreshRows(RefreshContext context) {
        PluginSettings current = context.settings();
        ArrayList<Player> players = new ArrayList<>(context.players());
        players.sort(
                Comparator.comparingInt((Player player) -> rowPriority(
                                current.afkIndicatorsEnabled()
                                        && afk.isAfk(player.getUniqueId())
                        ))
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(player -> player.getUniqueId().toString())
        );
        Set<UUID> online = new HashSet<>();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            boolean awayVisible = current.afkIndicatorsEnabled() && afk.isAfk(playerId);
            RowState state = new RowState(
                    index,
                    player.getName(),
                    awayVisible
            );
            if (state.equals(rowStates.put(playerId, state))) {
                continue;
            }
            Component formattedName = messages.formatPlayerName(player, settings);
            player.setPlayerListOrder(index);
            Component away = awayVisible
                    ? messages.component("player-list.afk-marker")
                    : Component.empty();
            player.playerListName(messages.component(
                    "player-list.entry",
                    Placeholder.component("player", formattedName),
                    Placeholder.component("afk", away)
            ));
        }
        rowStates.keySet().retainAll(online);
    }

    private void refreshPlayer(Player player, RefreshContext context) {
        var preferences = experience.preferences(player);
        PluginSettings current = context.settings();
        long unread = current.playerListShowUnreadNotifications()
                ? notifications.unread(player.getUniqueId())
                : 0L;
        World world = player.getWorld();
        boolean showWorld = current.playerListShowWorld();
        boolean worldDetails = showWorld
                && world.getEnvironment() == World.Environment.NORMAL;
        ViewState state = new ViewState(
                preferences.playerListEnabled(),
                preferences.language(),
                player.locale().getLanguage(),
                player.getName(),
                context.online(),
                context.active(),
                context.afk(),
                context.maximum(),
                context.greetingKey(),
                current.playerListShowPing() ? player.getPing() : 0,
                current.playerListShowTps() ? context.tpsTenths() : 0,
                current.playerListShowMspt() ? context.msptTenths() : 0,
                showWorld ? world.getUID() : null,
                showWorld ? world.getName() : null,
                showWorld ? world.getEnvironment() : null,
                worldDetails ? worldTimeMinutes(world.getTime()) : -1,
                worldDetails && world.hasStorm(),
                worldDetails && world.isThundering(),
                unread,
                current.afkIndicatorsEnabled(),
                current.playerListShowPing(),
                current.playerListShowTps(),
                current.playerListShowMspt(),
                current.playerListShowWorld(),
                current.playerListShowUnreadNotifications()
        );
        if (state.equals(viewStates.put(player.getUniqueId(), state))) {
            return;
        }
        if (!state.enabled()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            return;
        }
        player.sendPlayerListHeaderAndFooter(header(player, context), footer(player, context, unread));
    }

    private Component header(Player player, RefreshContext context) {
        return messages.component(
                player,
                "player-list.header",
                Placeholder.component("greeting", messages.component(player, context.greetingKey())),
                Placeholder.unparsed("player", player.getName())
        );
    }

    private Component footer(Player player, RefreshContext context, long unread) {
        PluginSettings current = context.settings();
        Component firstLine = messages.component(
                player,
                current.afkIndicatorsEnabled()
                        ? "player-list.online-with-afk"
                        : "player-list.online",
                Placeholder.unparsed("online", Integer.toString(context.online())),
                Placeholder.unparsed("active", Integer.toString(context.active())),
                Placeholder.unparsed("afk", Integer.toString(context.afk())),
                Placeholder.unparsed("maximum", Integer.toString(context.maximum()))
        );
        if (current.playerListShowPing()) {
            int ping = player.getPing();
            firstLine = firstLine.append(SEPARATOR).append(messages.component(
                    player,
                    "player-list.ping",
                    Placeholder.component("ping", metric(player, ping, pingColor(ping), 0))
            ));
        }

        List<Component> performance = new ArrayList<>();
        double tps = context.tpsTenths() / 10.0;
        if (current.playerListShowTps()) {
            performance.add(messages.component(
                    player,
                    "player-list.tps",
                    Placeholder.component("tps", metric(player, tps, tpsColor(tps), 1))
            ));
        }
        double mspt = context.msptTenths() / 10.0;
        if (current.playerListShowMspt()) {
            performance.add(messages.component(
                    player,
                    "player-list.mspt",
                    Placeholder.component("mspt", metric(player, mspt, msptColor(mspt), 1))
            ));
        }
        if (current.playerListShowWorld()) {
            performance.add(worldStatus(player));
        }

        Component footer = firstLine;
        if (!performance.isEmpty()) {
            footer = footer.append(Component.newline()).append(join(performance));
        }
        if (current.playerListShowUnreadNotifications() && unread > 0) {
            footer = footer.append(Component.newline()).append(messages.component(
                    player,
                    MessageService.plural("player-list.unread", unread),
                    Placeholder.unparsed("count", Long.toString(unread))
            ));
        }
        return footer;
    }

    private Component worldStatus(Player player) {
        World world = player.getWorld();
        String key = switch (world.getEnvironment()) {
            case NORMAL -> "player-list.world.overworld";
            case NETHER -> "player-list.world.nether";
            case THE_END -> "player-list.world.end";
            default -> "player-list.world.custom";
        };
        Component status = messages.component(
                player,
                key,
                Placeholder.unparsed("world", world.getName())
        );
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return status;
        }
        return status
                .append(SEPARATOR)
                .append(messages.component(
                        player,
                        "player-list.time",
                        Placeholder.unparsed("time", formatWorldTime(world.getTime()))
                ))
                .append(SEPARATOR)
                .append(messages.component(
                        player,
                        weatherKey(world.isThundering(), world.hasStorm())
                ));
    }

    private String greetingKey() {
        int hour = LocalTime.now(ZoneId.systemDefault()).getHour();
        if (hour < 5) {
            return "player-list.greeting.night";
        }
        if (hour < 12) {
            return "player-list.greeting.morning";
        }
        if (hour < 18) {
            return "player-list.greeting.day";
        }
        if (hour < 22) {
            return "player-list.greeting.evening";
        }
        return "player-list.greeting.night";
    }

    private double oneMinuteTps() {
        double[] samples = server.getTPS();
        if (samples.length == 0 || !Double.isFinite(samples[0])) {
            return 20.0;
        }
        return Math.min(20.0, Math.max(0.0, samples[0]));
    }

    static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    static boolean isAfkCommand(String message) {
        String commandLine = message.stripLeading().toLowerCase(Locale.ROOT);
        int separator = commandLine.indexOf(' ');
        String root = separator < 0 ? commandLine : commandLine.substring(0, separator);
        int namespace = root.indexOf(':');
        if (namespace >= 0) {
            root = "/" + root.substring(namespace + 1);
        }
        return root.equals("/afk") || root.equals("/away") || root.equals("/poissa");
    }

    static int rowPriority(boolean afk) {
        return afk ? 1 : 0;
    }

    static long worldTimeMinutes(long ticks) {
        long dayTicks = Math.floorMod(ticks, 24_000L);
        long minutes = ((dayTicks + 6_000L) % 24_000L) * 1_440L / 24_000L;
        return minutes - minutes % WORLD_TIME_STEP_MINUTES;
    }

    static String formatWorldTime(long ticks) {
        long minutes = worldTimeMinutes(ticks);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60L, minutes % 60L);
    }

    static String weatherKey(boolean thundering, boolean storming) {
        if (thundering) {
            return "player-list.weather.thunder";
        }
        return storming
                ? "player-list.weather.rain"
                : "player-list.weather.clear";
    }

    private Component metric(Player viewer, double value, NamedTextColor color, int decimals) {
        if (decimals == 0) {
            return Component.text(Long.toString(Math.round(value)), color);
        }
        return Component.text(DisplayFormat.decimal(messages, viewer, value, decimals), color);
    }

    private NamedTextColor pingColor(int ping) {
        if (ping <= 80) {
            return NamedTextColor.GREEN;
        }
        if (ping <= 160) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private NamedTextColor tpsColor(double tps) {
        if (tps >= 19.0) {
            return NamedTextColor.GREEN;
        }
        if (tps >= 17.0) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private NamedTextColor msptColor(double mspt) {
        if (mspt <= 40.0) {
            return NamedTextColor.GREEN;
        }
        if (mspt <= 50.0) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private Component join(List<Component> components) {
        Component result = Component.empty();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) {
                result = result.append(SEPARATOR);
            }
            result = result.append(components.get(index));
        }
        return result;
    }

    private void clearPresentation() {
        rowStates.clear();
        viewStates.clear();
        server.getOnlinePlayers().forEach(player -> {
            player.playerListName(null);
            player.setPlayerListOrder(0);
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        });
    }

    private void cancelRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    @Override
    public void close() {
        cancelRefreshTask();
        clearPresentation();
        server.getOnlinePlayers().forEach(player -> afk.left(player.getUniqueId()));
    }

    private long quantizeTenths(double value) {
        return Math.round(value * 10.0);
    }

    private record RefreshContext(
            PluginSettings settings,
            List<Player> players,
            int online,
            int active,
            int afk,
            int maximum,
            String greetingKey,
            long tpsTenths,
            long msptTenths
    ) {
    }

    private record RowState(
            int order,
            String playerName,
            boolean afk
    ) {
    }

    private record ViewState(
            boolean enabled,
            LanguagePreference language,
            String localeLanguage,
            String playerName,
            int online,
            int active,
            int afk,
            int maximum,
            String greetingKey,
            int ping,
            long tpsTenths,
            long msptTenths,
            UUID worldId,
            String worldName,
            World.Environment environment,
            long worldTimeMinutes,
            boolean storming,
            boolean thundering,
            long unread,
            boolean afkIndicators,
            boolean showPing,
            boolean showTps,
            boolean showMspt,
            boolean showWorld,
            boolean showUnread
    ) {
    }
}
