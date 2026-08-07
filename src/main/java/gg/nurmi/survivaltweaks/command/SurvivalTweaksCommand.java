package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import gg.nurmi.survivaltweaks.service.BackupService;
import gg.nurmi.survivaltweaks.service.CustomEnchantItemService;
import gg.nurmi.survivaltweaks.service.DiagnosticService;
import gg.nurmi.survivaltweaks.service.DisplayFormat;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.MaintenanceService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.FastLeafDecayService;
import gg.nurmi.survivaltweaks.service.PerformanceGovernor;
import gg.nurmi.survivaltweaks.service.PersistenceMonitor;
import gg.nurmi.survivaltweaks.service.PortableExportService;
import gg.nurmi.survivaltweaks.service.ReloadService;
import gg.nurmi.survivaltweaks.service.TaskFailureIsolation;
import gg.nurmi.survivaltweaks.service.TickWorkBudget;
import gg.nurmi.survivaltweaks.service.TreeFellerService;
import gg.nurmi.survivaltweaks.storage.StorageBackend;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import gg.nurmi.survivaltweaks.storage.SqlStorage;
import gg.nurmi.survivaltweaks.ui.PlayerHubController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SurvivalTweaksCommand implements CommandExecutor, TabCompleter {

    public static final String RELOAD_PERMISSION = "survivaltweaks.command.reload";
    public static final String DOCTOR_PERMISSION = "survivaltweaks.command.doctor";
    public static final String SPAWN_POOL_PERMISSION = "survivaltweaks.command.spawnpool";
    public static final String MAINTENANCE_PERMISSION = "survivaltweaks.command.maintenance";
    public static final String BACKUP_PERMISSION = "survivaltweaks.command.backup";
    public static final String ENCHANT_PERMISSION = "survivaltweaks.command.enchant";
    public static final String PERFORMANCE_PERMISSION = "survivaltweaks.command.performance";
    public static final String STORAGE_PERMISSION = "survivaltweaks.command.storage";
    private static final int MAX_DIAGNOSTIC_ISSUES = 20;

    private static final List<HelpEntry> HELP_ENTRIES = List.of(
            new HelpEntry("/afk", "survivaltweaks.command.afk", "admin.help.afk", true),
            new HelpEntry("/mail", "survivaltweaks.command.mail", "admin.help.mail", true),
            new HelpEntry("/profile ", "survivaltweaks.command.profile", "admin.help.profile", true),
            new HelpEntry("/welcome", "survivaltweaks.command.welcome", "admin.help.welcome", true),
            new HelpEntry("/guide", "survivaltweaks.command.guide", "admin.help.guide", true),
            new HelpEntry("/home", "survivaltweaks.command.home", "admin.help.home", true),
            new HelpEntry("/sethome ", "survivaltweaks.command.sethome", "admin.help.sethome", true),
            new HelpEntry("/deletehome ", "survivaltweaks.command.deletehome", "admin.help.deletehome", true),
            new HelpEntry("/teleport ", "survivaltweaks.command.teleport", "admin.help.teleport", true),
            new HelpEntry(
                    "/teleportaccept ",
                    "survivaltweaks.command.teleportaccept",
                    "admin.help.teleportaccept",
                    true
            ),
            new HelpEntry(
                    "/teleportinbox",
                    "survivaltweaks.command.teleportaccept",
                    "admin.help.teleportinbox",
                    true
            ),
            new HelpEntry(
                    "/deathlocation",
                    "survivaltweaks.command.deathlocation",
                    "admin.help.deathlocation",
                    true
            ),
            new HelpEntry("/stats ", "survivaltweaks.command.stats", "admin.help.stats", true),
            new HelpEntry("/lock", "survivaltweaks.command.lock", "admin.help.lock", true),
            new HelpEntry("/unlock", "survivaltweaks.command.unlock", "admin.help.unlock", true),
            new HelpEntry("/shout ", "survivaltweaks.command.shout", "admin.help.shout", false),
            new HelpEntry(
                    "/survivaltweaks reload",
                    RELOAD_PERMISSION,
                    "admin.help.reload",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks doctor",
                    DOCTOR_PERMISSION,
                    "admin.help.doctor",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks performance",
                    PERFORMANCE_PERMISSION,
                    "admin.help.performance",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks backup list",
                    BACKUP_PERMISSION,
                    "admin.help.backup",
                    false
            ),
            new HelpEntry(
                    "/survivaltweaks storage status",
                    STORAGE_PERMISSION,
                    "admin.help.storage",
                    false
            ),
            new HelpEntry(
                    "/survivaltweaks spawnpool status",
                    SPAWN_POOL_PERMISSION,
                    "admin.help.spawnpool",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks maintenance status",
                    MAINTENANCE_PERMISSION,
                    "admin.help.maintenance",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks restart status",
                    MAINTENANCE_PERMISSION,
                    "admin.help.restart",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks enchant ",
                    ENCHANT_PERMISSION,
                    "admin.help.enchant",
                    true
            ),
            new HelpEntry(
                    "/survivaltweaks config ",
                    RELOAD_PERMISSION,
                    "admin.help.config",
                    true
            )
    );

    private final MessageService messages;
    private final ReloadService reloads;
    private final DiagnosticService diagnostics;
    private final PlayerHubController hub;
    private final NewPlayerSpawnService spawnPool;
    private final MaintenanceService maintenance;
    private final BackupService backups;
    private final CustomEnchantItemService enchantments;
    private final JavaPlugin plugin;
    private final PerformanceGovernor governor;
    private final TickWorkBudget workBudget;
    private final TaskFailureIsolation taskFailures;
    private final TreeFellerService treeFeller;
    private final FastLeafDecayService leafDecay;
    private final StorageManager storage;
    private final PortableExportService portableExports;
    private final PersistenceMonitor persistenceMonitor;
    private final SettingsService settings;
    private final AtomicBoolean backupOperation = new AtomicBoolean();
    private final AtomicBoolean storageOperation = new AtomicBoolean();

    public SurvivalTweaksCommand(
            MessageService messages,
            ReloadService reloads,
            DiagnosticService diagnostics,
            PlayerHubController hub,
            NewPlayerSpawnService spawnPool,
            MaintenanceService maintenance,
            BackupService backups,
            CustomEnchantItemService enchantments,
            PerformanceGovernor governor,
            TickWorkBudget workBudget,
            TaskFailureIsolation taskFailures,
            TreeFellerService treeFeller,
            FastLeafDecayService leafDecay,
            StorageManager storage,
            PortableExportService portableExports,
            PersistenceMonitor persistenceMonitor,
            SettingsService settings,
            JavaPlugin plugin
    ) {
        this.messages = messages;
        this.reloads = reloads;
        this.diagnostics = diagnostics;
        this.hub = hub;
        this.spawnPool = spawnPool;
        this.maintenance = maintenance;
        this.backups = backups;
        this.enchantments = enchantments;
        this.governor = governor;
        this.workBudget = workBudget;
        this.taskFailures = taskFailures;
        this.treeFeller = treeFeller;
        this.leafDecay = leafDecay;
        this.storage = storage;
        this.portableExports = portableExports;
        this.persistenceMonitor = persistenceMonitor;
        this.settings = settings;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (arguments.length == 0 && sender instanceof Player player) {
            hub.open(player);
            return true;
        }
        if (arguments.length == 0
                || (arguments.length == 1 && arguments[0].equalsIgnoreCase("help"))) {
            showHelp(sender);
            return true;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(RELOAD_PERMISSION)) {
                messages.send(sender, "admin.no-permission");
                return true;
            }
            if (!reloads.reloadAsync(result -> {
                if (result.successful()) {
                    messages.send(sender, "admin.reload-success");
                } else {
                    messages.send(
                            sender,
                            "admin.reload-failed",
                            Placeholder.unparsed("reason", result.reason())
                    );
                }
            })) {
                messages.send(sender, "admin.reload-already-running");
            } else {
                messages.send(sender, "admin.reload-running");
            }
            return true;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("doctor")) {
            if (!sender.hasPermission(DOCTOR_PERMISSION)) {
                messages.send(sender, "admin.no-permission");
                return true;
            }
            if (!diagnostics.run(report -> showDiagnosticReport(sender, report))) {
                messages.send(sender, "admin.doctor.already-running");
            } else {
                messages.send(sender, "admin.doctor.running");
            }
            return true;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("performance")) {
            if (!sender.hasPermission(PERFORMANCE_PERMISSION)) {
                messages.send(sender, "admin.no-permission");
                return true;
            }
            showPerformance(sender);
            return true;
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("spawnpool")) {
            return handleSpawnPool(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("backup")) {
            return handleBackup(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("storage")) {
            return handleStorage(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("maintenance")) {
            return handleMaintenance(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("restart")) {
            return handleRestart(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("enchant")) {
            return handleEnchant(sender, arguments);
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("config")) {
            return handleConfig(sender, arguments);
        }

        messages.send(sender, "admin.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if ("help".startsWith(prefix)) {
                options.add("help");
            }
            if (sender.hasPermission(RELOAD_PERMISSION) && "reload".startsWith(prefix)) {
                options.add("reload");
            }
            if (sender.hasPermission(DOCTOR_PERMISSION) && "doctor".startsWith(prefix)) {
                options.add("doctor");
            }
            if (sender.hasPermission(PERFORMANCE_PERMISSION)
                    && "performance".startsWith(prefix)) {
                options.add("performance");
            }
            if (sender.hasPermission(SPAWN_POOL_PERMISSION) && "spawnpool".startsWith(prefix)) {
                options.add("spawnpool");
            }
            if (sender.hasPermission(BACKUP_PERMISSION) && "backup".startsWith(prefix)) {
                options.add("backup");
            }
            if (sender.hasPermission(STORAGE_PERMISSION) && "storage".startsWith(prefix)) {
                options.add("storage");
            }
            if (sender.hasPermission(MAINTENANCE_PERMISSION)) {
                if ("maintenance".startsWith(prefix)) {
                    options.add("maintenance");
                }
                if ("restart".startsWith(prefix)) {
                    options.add("restart");
                }
            }
            if (sender.hasPermission(ENCHANT_PERMISSION) && "enchant".startsWith(prefix)) {
                options.add("enchant");
            }
            if (sender.hasPermission(RELOAD_PERMISSION) && "config".startsWith(prefix)) {
                options.add("config");
            }
            return List.copyOf(options);
        }
        if (sender.hasPermission(ENCHANT_PERMISSION)
                && arguments[0].equalsIgnoreCase("enchant")) {
            if (arguments.length == 2) {
                String prefix = arguments[1].toLowerCase(Locale.ROOT);
                return java.util.Arrays.stream(CustomEnchantment.values())
                        .map(CustomEnchantment::key)
                        .filter(key -> key.startsWith(prefix))
                        .toList();
            }
            if (arguments.length == 3) {
                return CustomEnchantment.fromKey(arguments[1])
                        .map(enchantment -> java.util.stream.IntStream
                                .rangeClosed(1, enchantment.maxLevel())
                                .mapToObj(Integer::toString)
                                .filter(level -> level.startsWith(arguments[2]))
                                .toList())
                        .orElseGet(List::of);
            }
            return List.of();
        }
        if (sender.hasPermission(BACKUP_PERMISSION)
                && arguments[0].equalsIgnoreCase("backup")) {
            if (arguments.length == 2) {
                String prefix = arguments[1].toLowerCase(Locale.ROOT);
                return List.of("list", "create", "verify", "restore").stream()
                        .filter(option -> option.startsWith(prefix))
                        .toList();
            }
            if (arguments.length == 3
                    && (arguments[1].equalsIgnoreCase("verify")
                    || arguments[1].equalsIgnoreCase("restore"))) {
                String prefix = arguments[2].toLowerCase(Locale.ROOT);
                try {
                    return backups.archives().stream()
                            .map(BackupService.ArchiveInfo::filename)
                            .filter(filename -> filename.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();
                } catch (IOException exception) {
                    return List.of();
                }
            }
            if (arguments.length == 4
                    && arguments[1].equalsIgnoreCase("restore")
                    && "confirm".startsWith(arguments[3].toLowerCase(Locale.ROOT))) {
                return List.of("confirm");
            }
            return List.of();
        }
        if (sender.hasPermission(STORAGE_PERMISSION)
                && arguments[0].equalsIgnoreCase("storage")) {
            if (arguments.length == 2) {
                String prefix = arguments[1].toLowerCase(Locale.ROOT);
                return List.of("status", "verify", "export", "test", "migrate", "maintenance").stream()
                        .filter(option -> option.startsWith(prefix))
                        .toList();
            }
            if (arguments.length == 3
                    && (arguments[1].equalsIgnoreCase("test")
                    || arguments[1].equalsIgnoreCase("migrate"))) {
                String prefix = arguments[2].toLowerCase(Locale.ROOT);
                return List.of("sqlite", "postgresql", "mysql").stream()
                        .filter(option -> option.startsWith(prefix))
                        .toList();
            }
            if (arguments.length == 3
                    && arguments[1].equalsIgnoreCase("maintenance")) {
                String prefix = arguments[2].toLowerCase(Locale.ROOT);
                return List.of("preview", "run").stream()
                        .filter(option -> option.startsWith(prefix))
                        .toList();
            }
            if (arguments.length == 4
                    && arguments[1].equalsIgnoreCase("migrate")
                    && "confirm".startsWith(arguments[3].toLowerCase(Locale.ROOT))) {
                return List.of("confirm");
            }
            if (arguments.length == 4
                    && arguments[1].equalsIgnoreCase("maintenance")
                    && arguments[2].equalsIgnoreCase("run")
                    && "confirm".startsWith(arguments[3].toLowerCase(Locale.ROOT))) {
                return List.of("confirm");
            }
            return List.of();
        }
        if (sender.hasPermission(MAINTENANCE_PERMISSION)
                && arguments.length == 2
                && arguments[0].equalsIgnoreCase("maintenance")) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return List.of("status", "on", "off").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (sender.hasPermission(MAINTENANCE_PERMISSION)
                && arguments.length == 2
                && arguments[0].equalsIgnoreCase("restart")) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return List.of("status", "cancel", "30s", "5m", "1h").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (!sender.hasPermission(SPAWN_POOL_PERMISSION)
                || !arguments[0].equalsIgnoreCase("spawnpool")) {
            return List.of();
        }
        if (arguments.length == 2) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return List.of("status", "refill", "validate", "clear-prepared").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (arguments.length == 3
                && arguments[1].equalsIgnoreCase("clear-prepared")
                && "confirm".startsWith(arguments[2].toLowerCase(Locale.ROOT))) {
            return List.of("confirm");
        }
        
        if (sender.hasPermission(RELOAD_PERMISSION)
                && arguments[0].equalsIgnoreCase("config")) {
            if (arguments.length == 2) {
                return List.of("set", "get").stream()
                        .filter(option -> option.startsWith(arguments[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        
        return List.of();
    }
    
    private boolean handleConfig(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length < 3) {
            messages.send(sender, "admin.usage");
            return true;
        }
        String action = arguments[1];
        String path = arguments[2];
        if (action.equalsIgnoreCase("get")) {
            Object value = plugin.getConfig().get(path);
            String display = value == null ? "null" : value.toString();
            messages.send(sender, "admin.config.get", 
                    Placeholder.unparsed("path", path), 
                    Placeholder.unparsed("value", display));
            return true;
        } else if (action.equalsIgnoreCase("set")) {
            if (arguments.length < 4) {
                messages.send(sender, "admin.usage");
                return true;
            }
            String valueStr = String.join(" ", java.util.Arrays.copyOfRange(arguments, 3, arguments.length));
            Object parsedValue = valueStr;
            if (valueStr.equalsIgnoreCase("true") || valueStr.equalsIgnoreCase("false")) {
                parsedValue = Boolean.parseBoolean(valueStr);
            } else {
                try {
                    parsedValue = Integer.parseInt(valueStr);
                } catch (NumberFormatException e1) {
                    try {
                        parsedValue = Double.parseDouble(valueStr);
                    } catch (NumberFormatException e2) {
                        // string
                    }
                }
            }
            plugin.getConfig().set(path, parsedValue);
            plugin.saveConfig();
            
            reloads.reloadAsync(result -> {
                if (result.successful()) {
                    messages.send(sender, "admin.config.set-success",
                            Placeholder.unparsed("path", path),
                            Placeholder.unparsed("value", valueStr));
                } else {
                    messages.send(sender, "admin.reload-failed", Placeholder.unparsed("reason", result.reason()));
                }
            });
            return true;
        }
        return true;
    }

    private boolean handleEnchant(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(ENCHANT_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "admin.enchant.player-only");
            return true;
        }
        if (arguments.length < 2 || arguments.length > 3) {
            messages.send(sender, "admin.enchant.usage");
            return true;
        }

        CustomEnchantment enchantment = CustomEnchantment.fromKey(arguments[1]).orElse(null);
        if (enchantment == null) {
            messages.send(sender, "admin.enchant.unknown");
            return true;
        }
        int level = 1;
        if (arguments.length == 3) {
            try {
                level = Integer.parseInt(arguments[2]);
            } catch (NumberFormatException exception) {
                messages.send(sender, "admin.enchant.invalid-level");
                return true;
            }
        }
        if (level < 1 || level > enchantment.maxLevel()) {
            messages.send(sender, "admin.enchant.invalid-level");
            return true;
        }

        ItemStack book = enchantments.createBook(enchantment, level, player);
        player.getInventory().addItem(book).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        messages.send(
                player,
                "admin.enchant.given",
                Placeholder.component(
                        "enchantment",
                        messages.component(player, enchantment.nameKey())
                ),
                Placeholder.unparsed("level", Integer.toString(level))
        );
        return true;
    }

    private boolean handleBackup(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(BACKUP_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("list")) {
            runBackupOperation(sender, backups::archives, archives -> {
                if (archives.isEmpty()) {
                    messages.send(sender, "admin.backup.none");
                    return;
                }
                messages.send(
                        sender,
                        "admin.backup.list-header",
                        Placeholder.unparsed("count", Integer.toString(archives.size()))
                );
                archives.forEach(archive -> messages.send(
                        sender,
                        "admin.backup.list-entry",
                        Placeholder.unparsed("file", archive.filename()),
                        Placeholder.unparsed("kib", Long.toString((archive.size() + 1023L) / 1024L)),
                        Placeholder.unparsed("modified", archive.modified().toString())
                ));
            });
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("create")) {
            runBackupOperation(sender, () -> backups.create("manual"), created -> {
                if (created.isEmpty()) {
                    messages.send(sender, "admin.backup.empty");
                } else {
                    messages.send(
                            sender,
                            "admin.backup.created",
                            Placeholder.unparsed("file", created.orElseThrow().getFileName().toString())
                    );
                }
            });
            return true;
        }
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("verify")) {
            runBackupOperation(
                    sender,
                    () -> backups.verify(arguments[2]),
                    verification -> showBackupVerification(sender, arguments[2], verification)
            );
            return true;
        }
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("restore")) {
            runBackupOperation(sender, () -> backups.verify(arguments[2]), verification -> {
                if (!verification.valid()) {
                    showBackupVerification(sender, arguments[2], verification);
                    return;
                }
                messages.send(
                        sender,
                        "admin.backup.restore-confirm",
                        Placeholder.unparsed("file", arguments[2])
                );
            });
            return true;
        }
        if (arguments.length == 4
                && arguments[1].equalsIgnoreCase("restore")
                && arguments[3].equalsIgnoreCase("confirm")) {
            if (!(sender instanceof ConsoleCommandSender)) {
                messages.send(sender, "admin.backup.console-only");
                return true;
            }
            if (!maintenance.status().maintenanceMode()) {
                messages.send(sender, "admin.backup.maintenance-required");
                return true;
            }
            int online = plugin.getServer().getOnlinePlayers().size();
            if (online > 0) {
                messages.send(
                        sender,
                        MessageService.plural("admin.backup.players-online", online),
                        Placeholder.unparsed("count", Integer.toString(online))
                );
                return true;
            }
            runBackupOperation(sender, () -> backups.stageRestore(arguments[2]), verification -> {
                messages.send(
                        sender,
                        "admin.backup.restore-staged",
                        Placeholder.unparsed("file", arguments[2]),
                        Placeholder.unparsed("sha", verification.sha256())
                );
                plugin.getServer().getScheduler().runTaskLater(
                        plugin,
                        plugin.getServer()::shutdown,
                        1L
                );
            });
            return true;
        }
        messages.send(sender, "admin.backup.usage");
        return true;
    }

    private boolean handleStorage(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(STORAGE_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("status")) {
            runStorageOperation(sender, storage::status, status -> {
                messages.send(
                        sender,
                        status.healthy()
                                ? "admin.storage.status"
                                : "admin.storage.status-failed",
                        Placeholder.unparsed("backend", status.backend().key()),
                        Placeholder.unparsed("schema", Integer.toString(status.schemaVersion())),
                        Placeholder.unparsed("latency", Long.toString(status.latencyMillis())),
                        Placeholder.unparsed("active", Integer.toString(status.activeConnections())),
                        Placeholder.unparsed("idle", Integer.toString(status.idleConnections())),
                        Placeholder.unparsed("waiting", Integer.toString(status.waitingThreads())),
                        Placeholder.unparsed("reason", status.problem())
                );
                PersistenceMonitor.Snapshot writes = persistenceMonitor.snapshot();
                messages.send(
                        sender,
                        "admin.storage.write-status",
                        Placeholder.unparsed("pending", Integer.toString(writes.pending())),
                        Placeholder.unparsed("active", Integer.toString(writes.active())),
                        Placeholder.unparsed(
                                "oldest",
                                Long.toString(writes.oldestQueueMillis() / 1_000L)
                        )
                );
                PortableExportService.Status export = portableExports.status();
                messages.send(
                        sender,
                        export.enabled()
                                ? "admin.storage.export-status"
                                : "admin.storage.export-status-disabled",
                        Placeholder.unparsed(
                                "last",
                                export.lastSuccess() == null
                                        ? "-"
                                        : export.lastSuccess().toString()
                        ),
                        Placeholder.unparsed(
                                "next",
                                export.nextRun() == null ? "-" : export.nextRun().toString()
                        )
                );
            });
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("verify")) {
            runStorageOperation(sender, storage::verify, verification -> {
                if (verification.healthy()) {
                    messages.send(sender, "admin.storage.verify-valid");
                } else {
                    messages.send(
                            sender,
                            "admin.storage.verify-invalid",
                            Placeholder.unparsed(
                                    "problems",
                                    String.join("; ", verification.problems())
                            )
                    );
                }
            });
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("export")) {
            runStorageOperation(sender, storage::exportPortable, exported ->
                    messages.send(
                            sender,
                            "admin.storage.exported",
                            Placeholder.unparsed("file", exported.file().getFileName().toString()),
                            Placeholder.unparsed(
                                    "records",
                                    Integer.toString(exported.counts().total())
                            ),
                            Placeholder.unparsed("sha", exported.checksum())
                    )
            );
            return true;
        }
        if (arguments.length == 3
                && arguments[1].equalsIgnoreCase("maintenance")
                && arguments[2].equalsIgnoreCase("preview")) {
            runStorageOperation(sender, () -> storage.previewMaintenance(settings.current().mailPurgeInactiveDays()), preview ->
                    showMaintenancePreview(sender, preview)
            );
            return true;
        }
        if (arguments.length >= 3
                && arguments[1].equalsIgnoreCase("maintenance")
                && arguments[2].equalsIgnoreCase("run")) {
            if (!maintenance.status().maintenanceMode()) {
                messages.send(sender, "admin.storage.maintenance-mode-required");
                return true;
            }
            int online = plugin.getServer().getOnlinePlayers().size();
            if (online > 0) {
                messages.send(
                        sender,
                        "admin.storage.maintenance-players-online",
                        Placeholder.unparsed("count", Integer.toString(online))
                );
                return true;
            }
            if (arguments.length != 4 || !arguments[3].equalsIgnoreCase("confirm")) {
                messages.send(sender, "admin.storage.maintenance-confirm");
                return true;
            }
            runStorageOperation(sender, () -> storage.maintain(settings.current().mailPurgeInactiveDays()), result -> {
                messages.send(
                        sender,
                        "admin.storage.maintenance-complete",
                        Placeholder.unparsed(
                                "removed",
                                Integer.toString(result.removed().total())
                        ),
                        Placeholder.unparsed(
                                "file",
                                result.safetyExport().file().getFileName().toString()
                        ),
                        Placeholder.unparsed("sha", result.safetyExport().checksum())
                );
            });
            return true;
        }
        if (arguments.length >= 3
                && (arguments[1].equalsIgnoreCase("test")
                || arguments[1].equalsIgnoreCase("migrate"))) {
            StorageBackend target;
            try {
                target = StorageBackend.parse(arguments[2]);
            } catch (IllegalArgumentException exception) {
                messages.send(sender, "admin.storage.unknown-backend");
                return true;
            }
            if (target != StorageBackend.SQLITE) {
                String configuredType = plugin.getConfig()
                        .getString("storage.remote.type", "")
                        .strip();
                if (!configuredType.equalsIgnoreCase(target.key())) {
                    messages.send(
                            sender,
                            "admin.storage.remote-not-configured",
                            Placeholder.unparsed("backend", target.key())
                    );
                    return true;
                }
            }
            if (arguments[1].equalsIgnoreCase("test")) {
                runStorageOperation(sender, () -> storage.testBackend(target), result ->
                        messages.send(
                                sender,
                                "admin.storage.test-success",
                                Placeholder.unparsed("backend", result.backend().key()),
                                Placeholder.unparsed(
                                        "empty",
                                        Boolean.toString(result.empty())
                                ),
                                Placeholder.unparsed(
                                        "latency",
                                        Long.toString(result.latencyMillis())
                                )
                        )
                );
                return true;
            }
            if (arguments[1].equalsIgnoreCase("migrate")) {
                if (arguments.length != 4 || !arguments[3].equalsIgnoreCase("confirm")) {
                    messages.send(sender, "admin.storage.migration-confirm");
                    return true;
                }
                int online = plugin.getServer().getOnlinePlayers().size();
                if (online > 0) {
                    messages.send(
                            sender,
                            "admin.storage.migration-players-online",
                            Placeholder.unparsed("count", Integer.toString(online))
                    );
                    return true;
                }
                runStorageOperation(sender, () -> storage.stageMigration(target), migration -> {
                    plugin.getConfig().set("storage.backend", target.key());
                    plugin.saveConfig();
                    messages.send(
                            sender,
                            "admin.storage.migration-staged",
                            Placeholder.unparsed("source", migration.source().key()),
                            Placeholder.unparsed("target", migration.target().key()),
                            Placeholder.unparsed("id", migration.id().toString())
                    );
                    plugin.getServer().getScheduler().runTaskLater(
                            plugin,
                            plugin.getServer()::shutdown,
                            20L
                    );
                });
                return true;
            }
        }
        messages.send(sender, "admin.storage.usage");
        return true;
    }

    private void showMaintenancePreview(
            CommandSender sender,
            SqlStorage.MaintenancePreview preview
    ) {
        messages.send(
                sender,
                preview.total() == 0
                        ? "admin.storage.maintenance-clean"
                        : "admin.storage.maintenance-preview",
                Placeholder.unparsed("total", Integer.toString(preview.total())),
                Placeholder.unparsed(
                        "expired",
                        Integer.toString(preview.expiredDeathMarkers())
                ),
                Placeholder.unparsed(
                        "orphans",
                        Integer.toString(preview.total() - preview.expiredDeathMarkers())
                )
        );
    }

    private <T> void runStorageOperation(
            CommandSender sender,
            CheckedSupplier<T> operation,
            Consumer<T> completion
    ) {
        if (!storageOperation.compareAndSet(false, true)) {
            messages.send(sender, "admin.storage.busy");
            return;
        }
        messages.send(sender, "admin.storage.working");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = operation.get();
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    storageOperation.set(false);
                    String reason = Objects.requireNonNullElse(
                            exception.getMessage(),
                            exception.getClass().getSimpleName()
                    );
                    messages.send(
                            sender,
                            "admin.storage.failed",
                            Placeholder.unparsed("reason", reason)
                    );
                });
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                storageOperation.set(false);
                completion.accept(result);
            });
        });
    }

    private void backupFailed(CommandSender sender, Exception exception) {
        String reason = exception.getMessage();
        messages.send(
                sender,
                "admin.backup.failed",
                Placeholder.unparsed(
                        "reason",
                        reason == null || reason.isBlank()
                                ? exception.getClass().getSimpleName()
                                : reason
                )
        );
    }

    private void showBackupVerification(
            CommandSender sender,
            String filename,
            BackupService.Verification verification
    ) {
        if (verification.valid()) {
            messages.send(
                    sender,
                    "admin.backup.valid",
                    Placeholder.unparsed("file", filename),
                    Placeholder.unparsed("entries", Integer.toString(verification.entries())),
                    Placeholder.unparsed(
                            "kib",
                            Long.toString((verification.uncompressedBytes() + 1023L) / 1024L)
                    ),
                    Placeholder.unparsed("sha", verification.sha256())
            );
        } else {
            messages.send(
                    sender,
                    "admin.backup.invalid",
                    Placeholder.unparsed("file", filename),
                    Placeholder.unparsed("reason", verification.problem())
            );
        }
    }

    private <T> void runBackupOperation(
            CommandSender sender,
            CheckedSupplier<T> operation,
            Consumer<T> completion
    ) {
        if (!backupOperation.compareAndSet(false, true)) {
            messages.send(sender, "admin.backup.busy");
            return;
        }
        messages.send(sender, "admin.backup.working");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = operation.get();
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    backupOperation.set(false);
                    backupFailed(sender, exception);
                });
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                backupOperation.set(false);
                completion.accept(result);
            });
        });
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {

        T get() throws Exception;
    }

    private boolean handleMaintenance(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(MAINTENANCE_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("on")) {
            maintenance.maintenanceMode(true);
            messages.send(sender, "admin.maintenance.enabled");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("off")) {
            maintenance.maintenanceMode(false);
            messages.send(sender, "admin.maintenance.disabled");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("status")) {
            showMaintenanceStatus(sender);
            return true;
        }
        messages.send(sender, "admin.maintenance.usage");
        return true;
    }

    private boolean handleRestart(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(MAINTENANCE_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("status")) {
            showMaintenanceStatus(sender);
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("cancel")) {
            messages.send(
                    sender,
                    maintenance.cancelRestart()
                            ? "admin.restart.cancelled"
                            : "admin.restart.none"
            );
            return true;
        }
        if (arguments.length == 2) {
            Duration delay = MaintenanceService.parseDelay(arguments[1]);
            if (delay == null) {
                messages.send(sender, "admin.restart.usage");
                return true;
            }
            MaintenanceService.ScheduleResult result = maintenance.schedule(delay);
            String key = switch (result) {
                case SCHEDULED -> MessageService.plural("admin.restart.scheduled", delay.toSeconds());
                case ALREADY_SCHEDULED -> "admin.restart.already-scheduled";
                case INVALID -> "admin.restart.invalid";
            };
            messages.send(
                    sender,
                    key,
                    Placeholder.unparsed("seconds", Long.toString(delay.toSeconds()))
            );
            return true;
        }
        messages.send(sender, "admin.restart.usage");
        return true;
    }

    private void showMaintenanceStatus(CommandSender sender) {
        MaintenanceService.Status status = maintenance.status();
        messages.send(
                sender,
                "admin.maintenance.status",
                Placeholder.unparsed("maintenance", Boolean.toString(status.maintenanceMode())),
                Placeholder.unparsed("scheduled", Boolean.toString(status.restartScheduled())),
                Placeholder.unparsed("stopping", Boolean.toString(status.stopping())),
                Placeholder.unparsed("seconds", Long.toString(status.remainingSeconds())),
                Placeholder.unparsed("blocked", Boolean.toString(status.joinBlocked()))
        );
    }

    private boolean handleSpawnPool(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(SPAWN_POOL_PERMISSION)) {
            messages.send(sender, "admin.no-permission");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("status")) {
            showSpawnPoolStatus(sender, spawnPool.status());
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("refill")) {
            String resultKey = switch (spawnPool.requestRefill()) {
                case STARTED -> "started";
                case FULL -> "full";
                case ALREADY_RUNNING -> "already-running";
                case TPS_PAUSED -> "tps-paused";
                case DISABLED -> "disabled";
                case WORLD_UNAVAILABLE -> "world-unavailable";
            };
            messages.send(sender, "admin.spawnpool.refill." + resultKey);
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("validate")) {
            if (spawnPool.validate(report -> showSpawnPoolValidation(sender, report))) {
                messages.send(sender, "admin.spawnpool.validate-started");
            } else {
                messages.send(sender, "admin.spawnpool.validate-running");
            }
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("clear-prepared")) {
            messages.send(sender, "admin.spawnpool.clear-confirm");
            return true;
        }
        if (arguments.length == 3
                && arguments[1].equalsIgnoreCase("clear-prepared")
                && arguments[2].equalsIgnoreCase("confirm")) {
            NewPlayerSpawnService.ClearResult result = spawnPool.clearPrepared();
            if (result.successful()) {
                messages.send(
                        sender,
                        MessageService.plural("admin.spawnpool.cleared", result.cleared()),
                        Placeholder.unparsed("count", Integer.toString(result.cleared()))
                );
            } else {
                messages.send(sender, "admin.spawnpool.clear-failed");
            }
            return true;
        }
        messages.send(sender, "admin.spawnpool.usage");
        return true;
    }

    private void showSpawnPoolStatus(
            CommandSender sender,
            NewPlayerSpawnService.PoolStatus status
    ) {
        messages.send(sender, "admin.spawnpool.status-header");
        messages.send(
                sender,
                "admin.spawnpool.status-pool",
                Placeholder.unparsed("enabled", Boolean.toString(status.enabled())),
                Placeholder.unparsed("world", status.world().isBlank() ? "<automatic>" : status.world()),
                Placeholder.unparsed("ready", Integer.toString(status.ready())),
                Placeholder.unparsed("available", Integer.toString(status.available())),
                Placeholder.unparsed("target", Integer.toString(status.target())),
                Placeholder.unparsed("tps", DisplayFormat.decimal(messages, sender, status.tps(), 2))
        );
        messages.send(
                sender,
                "admin.spawnpool.status-assignments",
                Placeholder.unparsed("pending", Integer.toString(status.pendingAssignments())),
                Placeholder.unparsed("completed", Integer.toString(status.completedAssignments())),
                Placeholder.unparsed("retired", Integer.toString(status.retired())),
                Placeholder.unparsed("waiting", Integer.toString(status.waitingPlayers()))
        );
        String rejectionSummary = status.rejectionsThisRun().isEmpty()
                ? "none"
                : status.rejectionsThisRun().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT) + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        messages.send(
                sender,
                "admin.spawnpool.status-runtime",
                Placeholder.unparsed("generating", Boolean.toString(status.generating())),
                Placeholder.unparsed("validating", Boolean.toString(status.validating())),
                Placeholder.unparsed("generated", Long.toString(status.generatedThisRun())),
                Placeholder.unparsed("replacements", Long.toString(status.replacementsThisRun())),
                Placeholder.unparsed("pauses", Long.toString(status.tpsPausesThisRun())),
                Placeholder.unparsed("rejections", rejectionSummary)
        );
    }

    private void showSpawnPoolValidation(
            CommandSender sender,
            NewPlayerSpawnService.ValidationReport report
    ) {
        if (report.cancelled()) {
            messages.send(sender, "admin.spawnpool.validate-cancelled");
            return;
        }
        messages.send(
                sender,
                "admin.spawnpool.validate-result",
                Placeholder.unparsed("checked", Integer.toString(report.checked())),
                Placeholder.unparsed("removed", Integer.toString(report.preparedRemoved())),
                Placeholder.unparsed("pending", Integer.toString(report.invalidPending())),
                Placeholder.unparsed("unavailable", Integer.toString(report.unavailableWorlds())),
                Placeholder.unparsed("errors", Integer.toString(report.errors()))
        );
    }

    private void showDiagnosticReport(CommandSender sender, DiagnosticService.Report report) {
        messages.send(sender, "admin.doctor.header");
        messages.send(
                sender,
                "admin.doctor.summary",
                Placeholder.unparsed("errors", Integer.toString(report.errors())),
                Placeholder.unparsed("warnings", Integer.toString(report.warnings())),
                Placeholder.unparsed("profiles", Integer.toString(report.profiles())),
                Placeholder.unparsed("locks", Integer.toString(report.locks())),
                Placeholder.unparsed("deaths", Integer.toString(report.deathMarkers())),
                Placeholder.unparsed("backups", Integer.toString(report.backups()))
        );
        if (report.healthy()) {
            messages.send(sender, "admin.doctor.healthy");
            return;
        }
        report.issues().stream().limit(MAX_DIAGNOSTIC_ISSUES).forEach(issue ->
                messages.send(
                        sender,
                        issue.severity() == DiagnosticService.Severity.ERROR
                                ? "admin.doctor.error"
                                : "admin.doctor.warning",
                        Placeholder.unparsed("detail", issue.detail())
                )
        );
        int omitted = report.issues().size() - MAX_DIAGNOSTIC_ISSUES;
        if (omitted > 0) {
            messages.send(
                    sender,
                    MessageService.plural("admin.doctor.truncated", omitted),
                    Placeholder.unparsed("count", Integer.toString(omitted))
            );
        }
    }

    private void showPerformance(CommandSender sender) {
        PerformanceGovernor.Snapshot performance = governor.snapshot();
        TickWorkBudget.Snapshot budget = workBudget.snapshot();
        TreeFellerService.Workload trees = treeFeller.workload();
        NewPlayerSpawnService.PoolStatus spawns = spawnPool.status();
        List<TaskFailureIsolation.Failure> failures =
                taskFailures.recent(Duration.ofMinutes(10));
        String levelKey = "admin.performance.level."
                + performance.level().name().toLowerCase(Locale.ROOT);

        messages.send(sender, "admin.performance.header");
        messages.send(
                sender,
                "admin.performance.summary",
                Placeholder.component("level", messages.component(sender, levelKey)),
                Placeholder.unparsed(
                        "mspt",
                        DisplayFormat.decimal(messages, sender, performance.mspt(), 1)
                ),
                Placeholder.unparsed("cadence", Integer.toString(governor.cosmeticDivisor())),
                Placeholder.unparsed(
                        "particles",
                        Integer.toString((int) Math.round(governor.particleScale() * 100.0))
                ),
                Placeholder.unparsed(
                        "work",
                        Integer.toString((int) Math.round(governor.workScale() * 100.0))
                )
        );
        messages.send(
                sender,
                performance.level() == PerformanceGovernor.Level.NORMAL
                        ? "admin.performance.stable"
                        : "admin.performance.recovery",
                Placeholder.unparsed(
                        "seconds",
                        Integer.toString(performance.recoverySecondsRemaining())
                )
        );
        messages.send(
                sender,
                "admin.performance.budget",
                Placeholder.unparsed("used", Integer.toString(budget.usedTotal())),
                Placeholder.unparsed("limit", Integer.toString(budget.limit())),
                Placeholder.unparsed("remaining", Integer.toString(budget.remaining()))
        );
        messages.send(
                sender,
                "admin.performance.lanes",
                Placeholder.unparsed("tree", lane(budget, TickWorkBudget.Lane.TREE_FELLING)),
                Placeholder.unparsed("leaves", lane(budget, TickWorkBudget.Lane.LEAF_DECAY)),
                Placeholder.unparsed("spawn", lane(budget, TickWorkBudget.Lane.SPAWN_PREPARATION)),
                Placeholder.unparsed("guide", lane(budget, TickWorkBudget.Lane.DEATH_GUIDE)),
                Placeholder.unparsed("atmosphere", lane(budget, TickWorkBudget.Lane.ATMOSPHERE))
        );
        messages.send(
                sender,
                "admin.performance.queues",
                Placeholder.unparsed("tree-jobs", Integer.toString(trees.jobs())),
                Placeholder.unparsed("tree-blocks", Integer.toString(trees.blocks())),
                Placeholder.unparsed("leaves", Integer.toString(leafDecay.queuedLeaves())),
                Placeholder.unparsed("spawn-waiting", Integer.toString(spawns.waitingPlayers())),
                Placeholder.unparsed(
                        "spawn-generating",
                        Boolean.toString(spawns.generating())
                )
        );
        if (failures.isEmpty()) {
            messages.send(sender, "admin.performance.failures-none");
            return;
        }
        messages.send(
                sender,
                "admin.performance.failures-header",
                Placeholder.unparsed("count", Integer.toString(failures.size()))
        );
        failures.stream().limit(5).forEach(failure -> messages.send(
                sender,
                "admin.performance.failure",
                Placeholder.unparsed("subsystem", failure.subsystem()),
                Placeholder.unparsed("count", Long.toString(failure.count())),
                Placeholder.unparsed("problem", failure.problem())
        ));
    }

    private String lane(TickWorkBudget.Snapshot budget, TickWorkBudget.Lane lane) {
        return budget.used().getOrDefault(lane, 0)
                + "/"
                + budget.deferredThisTick().getOrDefault(lane, 0)
                + "/"
                + budget.deferredTotal().getOrDefault(lane, 0L);
    }

    private void showHelp(CommandSender sender) {
        messages.send(sender, "admin.help.header");
        boolean player = sender instanceof Player;
        for (HelpEntry entry : HELP_ENTRIES) {
            if (!sender.hasPermission(entry.permission()) || (player && !entry.availableToPlayers())) {
                continue;
            }
            Component hover = messages.component(sender, "admin.help.click");
            Component command = Component.text(entry.command().stripTrailing())
                    .clickEvent(ClickEvent.suggestCommand(entry.command()))
                    .hoverEvent(HoverEvent.showText(hover));
            sender.sendMessage(messages.component(
                    sender,
                    "admin.help.entry",
                    Placeholder.component("command", command),
                    Placeholder.component("description", messages.component(sender, entry.descriptionKey()))
            ));
        }
        messages.send(sender, "admin.help.footer");
    }

    private record HelpEntry(
            String command,
            String permission,
            String descriptionKey,
            boolean availableToPlayers
    ) {
    }
}
