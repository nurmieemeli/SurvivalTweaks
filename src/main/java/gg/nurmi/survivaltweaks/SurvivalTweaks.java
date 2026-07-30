package gg.nurmi.survivaltweaks;

import gg.nurmi.survivaltweaks.command.AfkCommand;
import gg.nurmi.survivaltweaks.command.MailCommand;
import gg.nurmi.survivaltweaks.command.ShoutCommand;
import gg.nurmi.survivaltweaks.command.SurvivalTweaksCommand;
import gg.nurmi.survivaltweaks.command.home.DeleteHomeCommand;
import gg.nurmi.survivaltweaks.command.home.HomeCommand;
import gg.nurmi.survivaltweaks.command.home.SetHomeCommand;
import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.command.lock.UnlockCommand;
import gg.nurmi.survivaltweaks.command.teleport.TeleportAcceptCommand;
import gg.nurmi.survivaltweaks.command.teleport.TeleportCommand;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.listener.ChatListener;
import gg.nurmi.survivaltweaks.listener.ConnectionListener;
import gg.nurmi.survivaltweaks.listener.ContainerLockListener;
import gg.nurmi.survivaltweaks.listener.TeleportSafetyListener;
import gg.nurmi.survivaltweaks.service.ActionBarService;
import gg.nurmi.survivaltweaks.service.BackupService;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.CustomDeathMessageService;
import gg.nurmi.survivaltweaks.service.CustomEnchantAcquisitionService;
import gg.nurmi.survivaltweaks.service.CustomEnchantEffectService;
import gg.nurmi.survivaltweaks.service.CustomEnchantItemService;
import gg.nurmi.survivaltweaks.service.DeathRecoveryService;
import gg.nurmi.survivaltweaks.service.DiagnosticService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.MaintenanceService;
import gg.nurmi.survivaltweaks.service.MailService;
import gg.nurmi.survivaltweaks.service.MentionService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.OnboardingService;
import gg.nurmi.survivaltweaks.service.JourneyService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.service.PlayerListService;
import gg.nurmi.survivaltweaks.service.PlayerStatisticsService;
import gg.nurmi.survivaltweaks.service.PerformanceGovernor;
import gg.nurmi.survivaltweaks.service.ServerListService;
import gg.nurmi.survivaltweaks.service.SleepVoteService;
import gg.nurmi.survivaltweaks.service.LockTargetStatusService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.service.AtmosphereService;
import gg.nurmi.survivaltweaks.service.BlockRefillService;
import gg.nurmi.survivaltweaks.service.DecorationProtectionService;
import gg.nurmi.survivaltweaks.service.FastLeafDecayService;
import gg.nurmi.survivaltweaks.service.PetProtectionService;
import gg.nurmi.survivaltweaks.service.ReloadService;
import gg.nurmi.survivaltweaks.service.TreeFellerService;
import gg.nurmi.survivaltweaks.service.TaskFailureIsolation;
import gg.nurmi.survivaltweaks.service.TickWorkBudget;
import gg.nurmi.survivaltweaks.service.VanillaGuideService;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import gg.nurmi.survivaltweaks.storage.ContainerLockStore;
import gg.nurmi.survivaltweaks.storage.DeathMarkerStore;
import gg.nurmi.survivaltweaks.storage.NewPlayerSpawnStore;
import gg.nurmi.survivaltweaks.ui.ConfirmationDialogService;
import gg.nurmi.survivaltweaks.ui.HomeMenuController;
import gg.nurmi.survivaltweaks.ui.JourneyMenuController;
import gg.nurmi.survivaltweaks.ui.LockControlPanelController;
import gg.nurmi.survivaltweaks.ui.LockListController;
import gg.nurmi.survivaltweaks.ui.MailboxController;
import gg.nurmi.survivaltweaks.ui.NotificationCenterController;
import gg.nurmi.survivaltweaks.ui.PlayerHubController;
import gg.nurmi.survivaltweaks.ui.PreferencesMenuController;
import gg.nurmi.survivaltweaks.ui.SocialProfileController;
import gg.nurmi.survivaltweaks.ui.StatisticsJournalController;
import gg.nurmi.survivaltweaks.ui.TeleportInboxController;
import gg.nurmi.survivaltweaks.ui.TextPromptService;
import gg.nurmi.survivaltweaks.ui.VanillaGuideController;
import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

public final class SurvivalTweaks extends JavaPlugin {

    private final Clock clock = Clock.systemUTC();

    private ProfileRepository profiles;
    private TeleportRequestService teleportRequests;
    private SafeTeleportService safeTeleports;
    private ContainerLockService containerLocks;
    private LockTargetStatusService lockTargetStatus;
    private SettingsService settings;
    private MessageService messages;
    private FeedbackService feedback;
    private BackupService backups;
    private DiagnosticService diagnostics;
    private DeathRecoveryService deathRecovery;
    private NewPlayerSpawnService newPlayerSpawns;
    private PlayerListService playerList;
    private SleepVoteService sleepVotes;
    private MaintenanceService maintenance;
    private ServerListService serverList;
    private WelcomeBackController welcomeBack;
    private FastLeafDecayService fastLeafDecay;
    private AtmosphereService atmosphere;
    private TreeFellerService treeFeller;
    private PerformanceGovernor performanceGovernor;
    private TaskFailureIsolation taskFailures;
    private TickWorkBudget workBudget;
    private BukkitTask autosaveTask;
    private BukkitTask purgeTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        backups = new BackupService(getDataFolder().toPath(), clock, getLogger());
        try {
            if (backups.hasPendingRestore()) {
                backups.create("pre-restore");
                backups.applyPendingRestore();
                reloadConfig();
            } else {
                backups.create("startup");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create or restore a safety backup before loading player data",
                    exception
            );
        }
        mergeConfigDefaults();

        PluginSettings initialSettings = PluginSettings.load(getConfig(), getLogger());
        settings = new SettingsService(initialSettings);
        taskFailures = new TaskFailureIsolation(getLogger(), clock);
        performanceGovernor = new PerformanceGovernor(this, settings, taskFailures);
        workBudget = new TickWorkBudget(
                settings,
                performanceGovernor,
                () -> getServer().getCurrentTick()
        );
        performanceGovernor.start();
        messages = new MessageService(this, getConfig(), getLogger());
        if (messages.migratedLegacyMessages()) {
            saveConfig();
        }
        feedback = new FeedbackService(getConfig(), getLogger());
        ProfileStore profileStore = new ProfileStore(
                getDataFolder().toPath().resolve("userdata"),
                getLogger(),
                worldName -> {
                    org.bukkit.World world = getServer().getWorld(worldName);
                    return world == null ? null : world.getUID();
                }
        );

        profiles = new ProfileRepository(profileStore, getLogger());
        PlayerExperienceService experience = new PlayerExperienceService(profiles);
        messages.languagePreference(playerId -> experience.preferences(playerId).language());
        feedback.preferenceProvider(experience::preferences);
        newPlayerSpawns = new NewPlayerSpawnService(
                this,
                new NewPlayerSpawnStore(
                        getDataFolder().toPath().resolve("new-player-spawns.yml"),
                        getLogger()
                ),
                messages,
                feedback,
                settings,
                workBudget,
                taskFailures
        );
        NotificationService notifications = new NotificationService(profiles, clock);
        MailService mail = new MailService(
                getServer(),
                settings,
                profiles,
                notifications,
                messages,
                feedback,
                clock
        );
        playerList = new PlayerListService(
                this,
                messages,
                settings,
                notifications,
                experience,
                clock,
                taskFailures
        );
        diagnostics = new DiagnosticService(this, clock, performanceGovernor, taskFailures);
        OnboardingService onboarding = new OnboardingService(profiles, messages);
        onboarding.guidancePreference(playerId ->
                experience.preferences(playerId).journeyGuidanceEnabled());
        maintenance = new MaintenanceService(
                this,
                settings,
                messages,
                feedback,
                profiles,
                backups,
                clock
        );
        serverList = new ServerListService(settings, messages, maintenance, getLogger());
        MentionService mentions = new MentionService(
                this,
                settings,
                messages,
                feedback,
                experience,
                playerList,
                clock
        );
        JourneyService journey = new JourneyService(this, settings, messages, onboarding);
        VanillaGuideService vanillaGuideHints = new VanillaGuideService(
                this,
                settings,
                onboarding,
                messages,
                feedback,
                clock
        );
        teleportRequests = new TeleportRequestService();
        containerLocks = new ContainerLockService(
                new ContainerLockStore(getDataFolder().toPath().resolve("locked-containers.yml"), getLogger()),
                getLogger()
        );
        purgeInactiveLocks(initialSettings);
        ContainerBlockResolver containerResolver = new ContainerBlockResolver();
        ActionBarService actionBars = new ActionBarService(clock);
        sleepVotes = new SleepVoteService(
                this,
                settings,
                messages,
                actionBars,
                experience,
                playerList
        );
        safeTeleports = new SafeTeleportService(
                this,
                messages,
                feedback,
                clock,
                settings,
                actionBars,
                experience
        );
        ConfirmationDialogService dialogs = new ConfirmationDialogService(
                this,
                messages,
                feedback,
                settings,
                experience
        );
        TextPromptService prompts = new TextPromptService(this, messages);
        HomeMenuController homeMenu = new HomeMenuController(
                getServer(),
                profiles,
                messages,
                safeTeleports,
                feedback,
                settings,
                prompts,
                dialogs
        );
        LockControlPanelController lockPanel = new LockControlPanelController(
                getServer(),
                containerResolver,
                containerLocks,
                messages,
                feedback,
                prompts,
                dialogs,
                clock,
                notifications,
                experience
        );
        lockTargetStatus = new LockTargetStatusService(
                this,
                containerResolver,
                containerLocks,
                messages,
                settings,
                actionBars,
                safeTeleports,
                experience,
                taskFailures
        );
        ReloadService reloads = new ReloadService(
                this,
                settings,
                messages,
                feedback,
                backups,
                this::applyReloadedSettings
        );
        deathRecovery = new DeathRecoveryService(
                this,
                new DeathMarkerStore(getDataFolder().toPath().resolve("death-markers.yml"), getLogger()),
                messages,
                settings,
                clock,
                feedback,
                notifications,
                onboarding,
                performanceGovernor,
                workBudget,
                taskFailures
        );

        TeleportAcceptCommand teleportAccept = new TeleportAcceptCommand(
                getServer(),
                teleportRequests,
                messages,
                feedback,
                safeTeleports,
                clock,
                notifications,
                settings
        );
        TeleportInboxController inbox = new TeleportInboxController(
                getServer(),
                teleportRequests,
                teleportAccept,
                messages,
                feedback,
                clock
        );
        teleportAccept.inbox(inbox);
        PreferencesMenuController preferences = new PreferencesMenuController(
                getServer(),
                experience,
                messages,
                feedback
        );
        preferences.onChanged(player -> {
            playerList.preferenceChanged(player);
            mentions.preferenceChanged(player);
        });
        NotificationCenterController notificationCenter = new NotificationCenterController(
                getServer(),
                profiles,
                messages,
                feedback,
                clock
        );
        MailboxController mailbox = new MailboxController(
                getServer(),
                profiles,
                mail,
                prompts,
                messages,
                feedback,
                clock
        );
        StatisticsJournalController statistics = new StatisticsJournalController(
                getServer(),
                settings,
                profiles,
                new PlayerStatisticsService(),
                messages,
                feedback
        );
        SocialProfileController socialProfile = new SocialProfileController(
                getServer(),
                profiles,
                settings,
                playerList,
                mailbox,
                statistics,
                messages,
                feedback,
                clock
        );
        LockListController lockList = new LockListController(
                getServer(),
                containerLocks,
                lockPanel,
                messages,
                feedback
        );
        VanillaGuideController vanillaGuide = new VanillaGuideController(
                getServer(),
                settings,
                profiles,
                messages,
                feedback
        );
        JourneyMenuController journeyMenu = new JourneyMenuController(
                getServer(),
                profiles,
                vanillaGuide,
                messages,
                feedback
        );
        welcomeBack = new WelcomeBackController(
                this,
                settings,
                profiles,
                notifications,
                teleportRequests,
                deathRecovery,
                notificationCenter,
                mailbox,
                inbox,
                messages,
                feedback,
                clock
        );
        PlayerHubController hub = new PlayerHubController(
                getServer(),
                profiles,
                homeMenu,
                inbox,
                deathRecovery,
                statistics,
                lockList,
                journeyMenu,
                mailbox,
                socialProfile,
                welcomeBack,
                notificationCenter,
                notifications,
                preferences,
                messages,
                feedback,
                onboarding
        );
        preferences.backTo(hub::open);
        notificationCenter.backTo(hub::open);
        lockList.backTo(hub::open);
        journeyMenu.backTo(hub::open);
        vanillaGuide.backTo(journeyMenu::open);
        mailbox.backTo(hub::open);
        statistics.backTo(hub::open);
        socialProfile.backTo(hub::open);
        welcomeBack.backTo(hub::open);
        lockPanel.backTo(lockList::open);
        homeMenu.backTo(hub::open);
        inbox.backTo(hub::open);

        getServer().getOnlinePlayers().forEach(player -> profiles.load(player.getUniqueId()));
        CustomEnchantItemService customEnchantments =
                new CustomEnchantItemService(this, messages);
        registerListeners(
                containerResolver,
                homeMenu,
                prompts,
                lockPanel,
                deathRecovery,
                inbox,
                preferences,
                notificationCenter,
                lockList,
                hub,
                journeyMenu,
                journey,
                vanillaGuide,
                vanillaGuideHints,
                mentions,
                mailbox,
                statistics,
                socialProfile,
                welcomeBack,
                notifications,
                experience,
                actionBars,
                customEnchantments
        );
        registerCommands(
                dialogs,
                homeMenu,
                containerResolver,
                reloads,
                deathRecovery,
                teleportAccept,
                inbox,
                hub,
                onboarding,
                notifications,
                diagnostics,
                mail,
                mailbox,
                statistics,
                socialProfile,
                welcomeBack,
                vanillaGuide,
                customEnchantments
        );
        startTasks(initialSettings, notifications);
        newPlayerSpawns.start();
        playerList.start();
        sleepVotes.start();

        getLogger().info("SurvivalTweaks enabled with " + initialSettings.maxHomes() + " homes per player.");
    }

    @Override
    public void onDisable() {
        cancel(autosaveTask);
        cancel(purgeTask);
        if (atmosphere != null) {
            atmosphere.close();
            atmosphere = null;
        }
        if (fastLeafDecay != null) {
            fastLeafDecay.close();
            fastLeafDecay = null;
        }
        if (treeFeller != null) {
            treeFeller.close();
            treeFeller = null;
        }
        if (diagnostics != null) {
            diagnostics.close();
            diagnostics = null;
        }
        if (maintenance != null) {
            maintenance.close();
            maintenance = null;
        }
        if (sleepVotes != null) {
            sleepVotes.close();
            sleepVotes = null;
        }
        if (playerList != null) {
            playerList.close();
            playerList = null;
        }
        if (newPlayerSpawns != null) {
            newPlayerSpawns.close();
            newPlayerSpawns = null;
        }
        if (lockTargetStatus != null) {
            lockTargetStatus.close();
            lockTargetStatus = null;
        }
        if (deathRecovery != null) {
            deathRecovery.close();
            deathRecovery = null;
        }
        if (safeTeleports != null) {
            safeTeleports.close();
            safeTeleports = null;
        }
        if (containerLocks != null) {
            containerLocks.close();
            containerLocks = null;
        }
        if (welcomeBack != null) {
            getServer().getOnlinePlayers().forEach(welcomeBack::playerLeaving);
            welcomeBack = null;
        }
        if (profiles != null) {
            profiles.close();
            profiles = null;
        }
        if (performanceGovernor != null) {
            performanceGovernor.close();
            performanceGovernor = null;
        }
        teleportRequests = null;
        settings = null;
        messages = null;
        feedback = null;
        backups = null;
        serverList = null;
        workBudget = null;
        taskFailures = null;
    }

    private void registerListeners(
            ContainerBlockResolver containerResolver,
            HomeMenuController homeMenu,
            TextPromptService prompts,
            LockControlPanelController lockPanel,
            DeathRecoveryService deathRecovery,
            TeleportInboxController inbox,
            PreferencesMenuController preferences,
            NotificationCenterController notificationCenter,
            LockListController lockList,
            PlayerHubController hub,
            JourneyMenuController journeyMenu,
            JourneyService journey,
            VanillaGuideController vanillaGuide,
            VanillaGuideService vanillaGuideHints,
            MentionService mentions,
            MailboxController mailbox,
            StatisticsJournalController statistics,
            SocialProfileController socialProfile,
            WelcomeBackController welcomeBack,
            NotificationService notifications,
            PlayerExperienceService experience,
            ActionBarService actionBars,
            CustomEnchantItemService customEnchantments
    ) {
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(
                new ConnectionListener(
                        profiles,
                        teleportRequests,
                        messages,
                        settings,
                        notifications,
                        newPlayerSpawns,
                        welcomeBack,
                        experience
                ),
                this
        );
        pluginManager.registerEvents(new ChatListener(messages, settings, mentions), this);
        pluginManager.registerEvents(newPlayerSpawns, this);
        pluginManager.registerEvents(playerList, this);
        pluginManager.registerEvents(sleepVotes, this);
        pluginManager.registerEvents(maintenance, this);
        pluginManager.registerEvents(serverList, this);
        pluginManager.registerEvents(mentions, this);
        pluginManager.registerEvents(journey, this);
        pluginManager.registerEvents(vanillaGuideHints, this);
        pluginManager.registerEvents(vanillaGuide, this);
        pluginManager.registerEvents(mailbox, this);
        pluginManager.registerEvents(statistics, this);
        pluginManager.registerEvents(socialProfile, this);
        pluginManager.registerEvents(welcomeBack, this);
        pluginManager.registerEvents(
                new TeleportSafetyListener(
                        safeTeleports,
                        settings
                ),
                this
        );
        pluginManager.registerEvents(
                new ContainerLockListener(
                        containerResolver,
                        containerLocks,
                        messages,
                        feedback,
                        settings,
                        clock,
                        notifications
                ),
                this
        );
        pluginManager.registerEvents(homeMenu, this);
        pluginManager.registerEvents(prompts, this);
        pluginManager.registerEvents(lockPanel, this);
        pluginManager.registerEvents(new CustomDeathMessageService(getServer(), messages, settings), this);
        pluginManager.registerEvents(deathRecovery, this);
        pluginManager.registerEvents(inbox, this);
        pluginManager.registerEvents(preferences, this);
        pluginManager.registerEvents(notificationCenter, this);
        pluginManager.registerEvents(lockList, this);
        pluginManager.registerEvents(hub, this);
        pluginManager.registerEvents(journeyMenu, this);
        pluginManager.registerEvents(
                new CustomEnchantAcquisitionService(customEnchantments, messages, feedback),
                this
        );
        pluginManager.registerEvents(
                new CustomEnchantEffectService(this, customEnchantments, feedback),
                this
        );
        treeFeller = new TreeFellerService(
                this,
                settings,
                customEnchantments,
                feedback,
                workBudget,
                taskFailures
        );
        pluginManager.registerEvents(treeFeller, this);
        fastLeafDecay = new FastLeafDecayService(this, settings, workBudget, taskFailures);
        pluginManager.registerEvents(fastLeafDecay, this);
        pluginManager.registerEvents(new PetProtectionService(settings), this);
        pluginManager.registerEvents(new BlockRefillService(this, settings), this);
        pluginManager.registerEvents(new DecorationProtectionService(settings), this);
        atmosphere = new AtmosphereService(
                this,
                settings,
                messages,
                actionBars,
                experience,
                performanceGovernor,
                workBudget,
                taskFailures
        );
        pluginManager.registerEvents(atmosphere, this);
    }

    private void registerCommands(
            ConfirmationDialogService dialogs,
            HomeMenuController homeMenu,
            ContainerBlockResolver containerResolver,
            ReloadService reloads,
            DeathRecoveryService deathRecovery,
            TeleportAcceptCommand teleportAccept,
            TeleportInboxController inbox,
            PlayerHubController hub,
            OnboardingService onboarding,
            NotificationService notifications,
            DiagnosticService diagnostics,
            MailService mail,
            MailboxController mailbox,
            StatisticsJournalController statistics,
            SocialProfileController socialProfile,
            WelcomeBackController welcomeBack,
            VanillaGuideController vanillaGuide,
            CustomEnchantItemService customEnchantments
    ) {
        register("teleport", new TeleportCommand(
                getServer(),
                teleportRequests,
                messages,
                feedback,
                safeTeleports,
                dialogs,
                teleportAccept,
                clock,
                settings,
                onboarding
        ));
        register("teleportaccept", teleportAccept);
        register("teleportinbox", inbox);
        register("home", new HomeCommand(profiles, messages, homeMenu, getServer(), feedback));
        register("sethome", new SetHomeCommand(profiles, messages, feedback, settings, onboarding));
        register("deletehome", new DeleteHomeCommand(profiles, messages, feedback));
        register("shout", new ShoutCommand(getServer(), messages, feedback));
        register("lock", new LockCommand(
                getServer(),
                containerResolver,
                containerLocks,
                messages,
                feedback,
                settings,
                onboarding,
                notifications
        ));
        register("unlock", new UnlockCommand(
                containerResolver,
                containerLocks,
                messages,
                feedback,
                dialogs,
                settings,
                notifications
        ));
        register(
                "survivaltweaks",
                new SurvivalTweaksCommand(
                        messages,
                        reloads,
                        diagnostics,
                        hub,
                        newPlayerSpawns,
                        maintenance,
                        backups,
                        customEnchantments,
                        performanceGovernor,
                        workBudget,
                        taskFailures,
                        treeFeller,
                        fastLeafDecay,
                        this
                )
        );
        register("deathlocation", deathRecovery);
        register("afk", new AfkCommand(playerList, messages));
        register("mail", new MailCommand(getServer(), mail, mailbox, messages, settings));
        register("stats", statistics);
        register("profile", socialProfile);
        register("welcome", welcomeBack);
        register("guide", vanillaGuide);
    }

    private void startTasks(PluginSettings settings, NotificationService notifications) {
        restartAutosave(settings);
        purgeTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> teleportRequests.removeExpired(clock.instant()).forEach(request -> {
                    org.bukkit.OfflinePlayer recipient = getServer().getOfflinePlayer(request.recipientId());
                    notifications.notify(
                            request.senderId(),
                            NotificationType.TELEPORT_EXPIRED,
                            recipient.getName() == null
                                    ? request.recipientId().toString().substring(0, 8)
                                    : recipient.getName(),
                            ""
                    );
                }),
                20L,
                20L
        );
    }

    private void restartAutosave(PluginSettings settings) {
        cancel(autosaveTask);
        long autosaveTicks = settings.autosaveInterval().toSeconds() * 20L;
        autosaveTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> {
                    profiles.saveAll();
                    profiles.evictOffline(getServer().getOnlinePlayers().stream()
                            .map(org.bukkit.entity.Player::getUniqueId)
                            .toList());
                },
                autosaveTicks,
                autosaveTicks
        );
    }

    private void applyReloadedSettings(PluginSettings reloaded) {
        restartAutosave(reloaded);
        purgeInactiveLocks(reloaded);
        if (newPlayerSpawns != null) {
            newPlayerSpawns.reconfigure();
        }
        if (playerList != null) {
            playerList.reconfigure();
        }
        if (sleepVotes != null) {
            sleepVotes.reconfigure();
        }
        if (serverList != null) {
            serverList.reconfigure();
        }
    }

    private void purgeInactiveLocks(PluginSettings current) {
        if (containerLocks == null || current.purgeInactiveLocksDays() <= 0) {
            return;
        }
        int purged = containerLocks.purgeInactiveLocks(
                getServer(),
                current.purgeInactiveLocksDays()
        );
        if (purged > 0) {
            getLogger().info("Purged " + purged + " inactive container locks.");
        }
    }

    private void register(String name, CommandExecutor executor) {
        PluginCommand command = Objects.requireNonNull(
                getCommand(name),
                "Command '" + name + "' is missing from plugin.yml"
        );
        command.setExecutor(executor);
        if (executor instanceof TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
        describe(command, name);
    }

    /**
     * Bukkit keeps one server-wide description and usage string per command and
     * cannot localize them per player, so both are read from the catalogs here
     * rather than duplicated in plugin.yml. A null audience selects English;
     * players get translated text from {@code /survivaltweaks help} instead.
     */
    private void describe(PluginCommand command, String name) {
        command.setDescription(messages.plain(null, "admin.help." + name));
        command.setUsage(messages.plain(null, "command.usage." + name));
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private void mergeConfigDefaults() {
        FileConfiguration config = getConfig();
        Configuration defaults = config.getDefaults();
        boolean missingDefaults = defaults != null && defaults.getKeys(true).stream()
                .anyMatch(path -> !config.contains(path, true));
        config.options().copyDefaults(true);
        if (missingDefaults) {
            saveConfig();
        }
    }
}
