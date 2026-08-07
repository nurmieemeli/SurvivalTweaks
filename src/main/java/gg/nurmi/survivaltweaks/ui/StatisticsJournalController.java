package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.DisplayFormat;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerStatisticsService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class StatisticsJournalController implements Listener, CommandExecutor, TabCompleter {

    private static final int OVERVIEW = 10;
    private static final int TRAVEL = 12;
    private static final int COMBAT = 14;
    private static final int ACTIVITIES = 16;
    private static final int FAVORITE_TOOLS = 20;
    private static final int BACK = 22;

    private final Plugin plugin;
    private final Server server;
    private final SettingsService settings;
    private final ProfileRepository profiles;
    private final PlayerStatisticsService statistics;
    private final MessageService messages;
    private final FeedbackService feedback;
    private Consumer<Player> defaultBack = Player::closeInventory;

    public StatisticsJournalController(
            Plugin plugin,
            Server server,
            SettingsService settings,
            ProfileRepository profiles,
            PlayerStatisticsService statistics,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.plugin = plugin;
        this.server = server;
        this.settings = settings;
        this.profiles = profiles;
        this.statistics = statistics;
        this.messages = messages;
        this.feedback = feedback;
    }

    public void backTo(Consumer<Player> action) {
        defaultBack = action;
    }

    public void open(Player viewer, OfflinePlayer target) {
        open(viewer, target, defaultBack);
    }

    public void open(
            Player viewer,
            OfflinePlayer target,
            Consumer<Player> back
    ) {
        if (!settings.current().statisticsEnabled()) {
            messages.send(viewer, "statistics.disabled");
            return;
        }
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            messages.send(viewer, "statistics.unknown");
            return;
        }
        
        profiles.loadAsync(target.getUniqueId()).thenAcceptAsync(targetProfile -> {
            PlayerStatisticsService.Snapshot snapshot = statistics.snapshot(target);
            server.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                
                boolean own = viewer.getUniqueId().equals(target.getUniqueId());
                if (!own
                        && (!settings.current().statisticsPublicViewing()
                        || !targetProfile.preferences().publicProfileEnabled())
                        && !viewer.hasPermission(SocialProfileController.BYPASS_PERMISSION)) {
                    messages.send(viewer, "statistics.private");
                    return;
                }
                String targetName = target.getName() == null
                        ? targetProfile.lastKnownName()
                        : target.getName();
                if (targetName.isBlank()) {
                    targetName = target.getUniqueId().toString().substring(0, 8);
                }

        StatisticsHolder holder = new StatisticsHolder(
                viewer.getUniqueId(),
                back,
                server,
                messages.component(
                        viewer,
                        "ui.statistics.title",
                        Placeholder.unparsed("player", targetName)
                )
        );
        Inventory inventory = holder.getInventory();
        inventory.setItem(4, head(
                target,
                messages.component(
                        viewer,
                        "ui.statistics.player",
                        Placeholder.unparsed("player", targetName)
                ),
                List.of(messages.component(viewer, "ui.statistics.native-data"))
        ));
        inventory.setItem(OVERVIEW, item(
                Material.CLOCK,
                messages.component(viewer, "ui.statistics.overview"),
                overview(viewer, snapshot.overview())
        ));
        inventory.setItem(TRAVEL, item(
                Material.LEATHER_BOOTS,
                messages.component(viewer, "ui.statistics.travel"),
                travel(viewer, snapshot.travel())
        ));
        inventory.setItem(COMBAT, item(
                Material.IRON_SWORD,
                messages.component(viewer, "ui.statistics.combat"),
                combat(viewer, snapshot.combat())
        ));
        inventory.setItem(ACTIVITIES, item(
                Material.CRAFTING_TABLE,
                messages.component(viewer, "ui.statistics.activities"),
                activities(viewer, snapshot.activities())
        ));
        inventory.setItem(FAVORITE_TOOLS, item(
                Material.DIAMOND_PICKAXE,
                messages.component(viewer, "ui.statistics.favorite-tools"),
                favoriteTools(viewer, snapshot.favoriteTools())
        ));
        inventory.setItem(BACK, item(
                Material.ARROW,
                messages.component(viewer, "ui.common.back"),
                List.of()
        ));
        viewer.openInventory(inventory);
        feedback.play(viewer, FeedbackService.UI_OPEN);
            });
        });
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
            messages.send(player, "statistics.usage");
            return true;
        }
        OfflinePlayer target = arguments.length == 0 ? player : find(arguments[0]);
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
        if (!(event.getView().getTopInventory().getHolder(false) instanceof StatisticsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player
                && player.getUniqueId().equals(holder.viewerId())
                && event.getRawSlot() == BACK) {
            feedback.play(player, FeedbackService.UI_CLICK);
            holder.back().accept(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof StatisticsHolder) {
            event.setCancelled(true);
        }
    }

    private List<Component> overview(
            Player viewer,
            PlayerStatisticsService.Overview values
    ) {
        return List.of(
                line(viewer, "playtime", DisplayFormat.playtime(messages, viewer, values.playTimeTicks())),
                line(viewer, "world-time", DisplayFormat.playtime(messages, viewer, values.worldTimeTicks())),
                line(viewer, "deaths", values.deaths()),
                line(viewer, "mob-kills", values.mobKills()),
                line(viewer, "player-kills", values.playerKills()),
                line(viewer, "jumps", values.jumps()),
                line(viewer, "times-slept", values.timesSlept())
        );
    }

    private List<Component> travel(
            Player viewer,
            PlayerStatisticsService.Travel values
    ) {
        return List.of(
                line(viewer, "walked", DisplayFormat.distance(messages, viewer, values.walked())),
                line(viewer, "sprinted", DisplayFormat.distance(messages, viewer, values.sprinted())),
                line(viewer, "crouched", DisplayFormat.distance(messages, viewer, values.crouched())),
                line(viewer, "swum", DisplayFormat.distance(messages, viewer, values.swum())),
                line(viewer, "boat", DisplayFormat.distance(messages, viewer, values.boated())),
                line(viewer, "minecart", DisplayFormat.distance(messages, viewer, values.minecart())),
                line(viewer, "elytra", DisplayFormat.distance(messages, viewer, values.elytra())),
                line(viewer, "horseback", DisplayFormat.distance(messages, viewer, values.horseback()))
        );
    }

    private List<Component> combat(
            Player viewer,
            PlayerStatisticsService.Combat values
    ) {
        return List.of(
                line(viewer, "mob-kills", values.mobKills()),
                line(viewer, "player-kills", values.playerKills()),
                line(viewer, "deaths", values.deaths()),
                line(viewer, "damage-dealt", DisplayFormat.damage(messages, viewer, values.damageDealt())),
                line(viewer, "damage-taken", DisplayFormat.damage(messages, viewer, values.damageTaken())),
                line(viewer, "damage-blocked", DisplayFormat.damage(messages, viewer, values.damageBlocked())),
                line(viewer, "raids-won", values.raidsWon()),
                line(viewer, "targets-hit", values.targetsHit())
        );
    }

    private List<Component> activities(
            Player viewer,
            PlayerStatisticsService.Activities values
    ) {
        return List.of(
                line(viewer, "animals-bred", values.animalsBred()),
                line(viewer, "fish-caught", values.fishCaught()),
                line(viewer, "items-enchanted", values.itemsEnchanted()),
                line(viewer, "villager-trades", values.villagerTrades()),
                line(viewer, "villagers-talked-to", values.villagersTalkedTo()),
                line(viewer, "chests-opened", values.chestsOpened()),
                line(viewer, "ender-chests-opened", values.enderChestsOpened()),
                line(viewer, "shulkers-opened", values.shulkersOpened())
        );
    }

    private List<Component> favoriteTools(
            Player viewer,
            List<PlayerStatisticsService.ToolUse> tools
    ) {
        if (tools.isEmpty()) {
            return List.of(messages.component(viewer, "ui.statistics.no-tool-data"));
        }
        List<Component> lore = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            PlayerStatisticsService.ToolUse tool = tools.get(index);
            lore.add(messages.component(
                    viewer,
                    "ui.statistics.tool-line",
                    Placeholder.unparsed("rank", Integer.toString(index + 1)),
                    Placeholder.component(
                            "tool",
                            Component.translatable(tool.material().translationKey())
                    ),
                    Placeholder.unparsed("uses", Integer.toString(tool.uses()))
            ));
        }
        return List.copyOf(lore);
    }

    private Component line(Player viewer, String label, Object value) {
        return messages.component(
                viewer,
                "ui.statistics.line",
                Placeholder.component(
                        "label",
                        messages.component(viewer, "ui.statistics.label." + label)
                ),
                Placeholder.unparsed("value", value.toString())
        );
    }

    private Component line(Player viewer, String label, Component value) {
        return messages.component(
                viewer,
                "ui.statistics.line",
                Placeholder.component(
                        "label",
                        messages.component(viewer, "ui.statistics.label." + label)
                ),
                Placeholder.component("value", value)
        );
    }

    private OfflinePlayer find(String name) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
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

    private static final class StatisticsHolder implements SurvivalTweaksMenu {

        private final UUID viewerId;
        private final Consumer<Player> back;
        private final Inventory inventory;

        private StatisticsHolder(
                UUID viewerId,
                Consumer<Player> back,
                Server server,
                Component title
        ) {
            this.viewerId = viewerId;
            this.back = back;
            this.inventory = server.createInventory(this, 27, title);
        }

        private UUID viewerId() {
            return viewerId;
        }

        private Consumer<Player> back() {
            return back;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
