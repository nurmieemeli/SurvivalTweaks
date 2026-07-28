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

    private final JavaPlugin plugin;
    private final Server server;
    private final MessageService messages;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final PlayerExperienceService experience;
    private final AfkTracker afk;
    private final Map<UUID, RowState> rowStates = new HashMap<>();
    private final Map<UUID, ViewState> viewStates = new HashMap<>();
    private BukkitTask refreshTask;

    public PlayerListService(
            JavaPlugin plugin,
            MessageService messages,
            SettingsService settings,
            NotificationService notifications,
            PlayerExperienceService experience,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.experience = Objects.requireNonNull(experience, "experience");
        this.afk = new AfkTracker(Objects.requireNonNull(clock, "clock"));
    }

    public void start() {
        server.getOnlinePlayers().forEach(player -> afk.joined(player.getUniqueId()));
        reconfigure();
    }

    public void reconfigure() {
        cancelRefreshTask();
        rowStates.clear();
        viewStates.clear();
        if (!settings.current().playerListEnabled()) {
            clearPresentation();
            return;
        }
        refresh();
        long interval = settings.current().playerListRefreshSeconds() * 20L;
        refreshTask = server.getScheduler().runTaskTimer(plugin, this::refresh, interval, interval);
    }

    public AfkTracker.State toggleAfk(Player player) {
        if (!settings.current().playerListEnabled()
                || !settings.current().afkIndicatorsEnabled()) {
            return null;
        }
        AfkTracker.State state = afk.toggle(player.getUniqueId());
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
        server.getScheduler().runTask(plugin, this::refresh);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        afk.left(event.getPlayer().getUniqueId());
        rowStates.remove(event.getPlayer().getUniqueId());
        viewStates.remove(event.getPlayer().getUniqueId());
        server.getScheduler().runTask(plugin, this::refresh);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasExplicitlyChangedPosition() || event.hasChangedOrientation()) {
            if (afk.movementActivity(event.getPlayer().getUniqueId())) {
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
            server.getScheduler().runTask(plugin, this::refresh);
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
        if (current.afkIndicatorsEnabled()) {
            afk.updateAutomatic(online, current.afkTimeout());
        }
        RefreshContext context = new RefreshContext(
                current,
                players,
                players.size(),
                server.getMaxPlayers(),
                greetingKey(),
                quantizeTenths(oneMinuteTps()),
                quantizeTenths(finiteNonNegative(server.getAverageTickTime()))
        );
        refreshRows(context);
        players.forEach(player -> refreshPlayer(player, context));
    }

    private void activity(Player player) {
        if (afk.activity(player.getUniqueId())) {
            refresh();
        }
    }

    private void refreshRows(RefreshContext context) {
        PluginSettings current = context.settings();
        ArrayList<Player> players = new ArrayList<>(context.players());
        players.sort(
                Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(player -> player.getUniqueId().toString())
        );
        Set<UUID> online = new HashSet<>();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            boolean staffVisible = current.playerListStaffBadges()
                    && player.hasPermission("survivaltweaks.playerlist.staff");
            boolean awayVisible = current.afkIndicatorsEnabled() && afk.isAfk(playerId);
            RowState state = new RowState(
                    index,
                    player.getName(),
                    player.getWorld().getUID(),
                    player.getWorld().getName(),
                    player.getWorld().getEnvironment(),
                    staffVisible,
                    awayVisible
            );
            if (state.equals(rowStates.put(playerId, state))) {
                continue;
            }
            player.setPlayerListOrder(index);
            Component staff = staffVisible
                    ? messages.component("player-list.staff-marker")
                    : Component.empty();
            Component away = awayVisible
                    ? messages.component("player-list.afk-marker")
                    : Component.empty();
            player.playerListName(messages.component(
                    "player-list.entry",
                    Placeholder.component("world", worldMarker(player.getWorld())),
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.component("staff", staff),
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
        ViewState state = new ViewState(
                preferences.playerListEnabled(),
                preferences.language(),
                player.locale().getLanguage(),
                player.getName(),
                context.online(),
                context.maximum(),
                context.greetingKey(),
                current.playerListShowPing() ? player.getPing() : 0,
                context.tpsTenths(),
                context.msptTenths(),
                player.getWorld().getUID(),
                player.getWorld().getName(),
                player.getWorld().getEnvironment(),
                unread,
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
                "player-list.online",
                Placeholder.unparsed("online", Integer.toString(context.online())),
                Placeholder.unparsed("maximum", Integer.toString(context.maximum()))
        );
        if (current.playerListShowPing()) {
            int ping = player.getPing();
            firstLine = firstLine.append(SEPARATOR).append(messages.component(
                    player,
                    "player-list.ping",
                    Placeholder.component("ping", metric(ping, pingColor(ping), 0))
            ));
        }

        List<Component> performance = new ArrayList<>();
        double tps = context.tpsTenths() / 10.0;
        if (current.playerListShowTps()) {
            performance.add(messages.component(
                    player,
                    "player-list.tps",
                    Placeholder.component("tps", metric(tps, tpsColor(tps), 1))
            ));
        }
        double mspt = context.msptTenths() / 10.0;
        if (current.playerListShowMspt()) {
            performance.add(messages.component(
                    player,
                    "player-list.mspt",
                    Placeholder.component("mspt", metric(mspt, msptColor(mspt), 1))
            ));
        }
        if (current.playerListShowWorld()) {
            performance.add(worldName(player));
        }

        Component footer = firstLine;
        if (!performance.isEmpty()) {
            footer = footer.append(Component.newline()).append(join(performance));
        }
        if (current.playerListShowUnreadNotifications() && unread > 0) {
            footer = footer.append(Component.newline()).append(messages.component(
                    player,
                    "player-list.unread",
                    Placeholder.unparsed("count", Long.toString(unread))
            ));
        }
        return footer;
    }

    private Component worldName(Player player) {
        World world = player.getWorld();
        String key = switch (world.getEnvironment()) {
            case NORMAL -> "player-list.world.overworld";
            case NETHER -> "player-list.world.nether";
            case THE_END -> "player-list.world.end";
            default -> "player-list.world.custom";
        };
        return messages.component(
                player,
                key,
                Placeholder.unparsed("world", world.getName())
        );
    }

    private Component worldMarker(World world) {
        String key = switch (world.getEnvironment()) {
            case NORMAL -> "player-list.marker.overworld";
            case NETHER -> "player-list.marker.nether";
            case THE_END -> "player-list.marker.end";
            default -> "player-list.marker.custom";
        };
        return messages.component(key);
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

    private double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private boolean isAfkCommand(String message) {
        String commandLine = message.stripLeading().toLowerCase(Locale.ROOT);
        int separator = commandLine.indexOf(' ');
        String root = separator < 0 ? commandLine : commandLine.substring(0, separator);
        int namespace = root.indexOf(':');
        if (namespace >= 0) {
            root = "/" + root.substring(namespace + 1);
        }
        return root.equals("/afk") || root.equals("/away") || root.equals("/poissa");
    }

    private Component metric(double value, NamedTextColor color, int decimals) {
        if (decimals == 0) {
            return Component.text(Long.toString(Math.round(value)), color);
        }
        long tenths = Math.round(value * 10.0);
        return Component.text((tenths / 10L) + "." + Math.abs(tenths % 10L), color);
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
            int maximum,
            String greetingKey,
            long tpsTenths,
            long msptTenths
    ) {
    }

    private record RowState(
            int order,
            String playerName,
            UUID worldId,
            String worldName,
            World.Environment environment,
            boolean staff,
            boolean afk
    ) {
    }

    private record ViewState(
            boolean enabled,
            LanguagePreference language,
            String localeLanguage,
            String playerName,
            int online,
            int maximum,
            String greetingKey,
            int ping,
            long tpsTenths,
            long msptTenths,
            UUID worldId,
            String worldName,
            World.Environment environment,
            long unread,
            boolean showPing,
            boolean showTps,
            boolean showMspt,
            boolean showWorld,
            boolean showUnread
    ) {
    }
}
