package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.service.DeathRecoveryService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.OnboardingService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class PlayerHubController implements Listener {

    private static final int HOMES = 10;
    private static final int TELEPORTS = 12;
    private static final int DEATH = 14;
    private static final int STATISTICS = 15;
    private static final int LOCKS = 16;
    private static final int MAIL = 19;
    private static final int JOURNEY = 20;
    private static final int NOTIFICATIONS = 21;
    private static final int HELP = 22;
    private static final int PREFERENCES = 23;
    private static final int PROFILE = 24;
    private static final int WELCOME_BACK = 25;
    private static final int CLOSE = 26;

    private final Server server;
    private final ProfileRepository profiles;
    private final HomeMenuController homes;
    private final TeleportInboxController teleports;
    private final DeathRecoveryService deathRecovery;
    private final StatisticsJournalController statistics;
    private final LockListController locks;
    private final JourneyMenuController journey;
    private final MailboxController mailbox;
    private final SocialProfileController socialProfile;
    private final WelcomeBackController welcomeBack;
    private final NotificationCenterController notifications;
    private final NotificationService notificationService;
    private final PreferencesMenuController preferences;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final OnboardingService onboarding;

    public PlayerHubController(
            Server server,
            ProfileRepository profiles,
            HomeMenuController homes,
            TeleportInboxController teleports,
            DeathRecoveryService deathRecovery,
            StatisticsJournalController statistics,
            LockListController locks,
            JourneyMenuController journey,
            MailboxController mailbox,
            SocialProfileController socialProfile,
            WelcomeBackController welcomeBack,
            NotificationCenterController notifications,
            NotificationService notificationService,
            PreferencesMenuController preferences,
            MessageService messages,
            FeedbackService feedback,
            OnboardingService onboarding
    ) {
        this.server = server;
        this.profiles = profiles;
        this.homes = homes;
        this.teleports = teleports;
        this.deathRecovery = deathRecovery;
        this.statistics = statistics;
        this.locks = locks;
        this.journey = journey;
        this.mailbox = mailbox;
        this.socialProfile = socialProfile;
        this.welcomeBack = welcomeBack;
        this.notifications = notifications;
        this.notificationService = notificationService;
        this.preferences = preferences;
        this.messages = messages;
        this.feedback = feedback;
        this.onboarding = onboarding;
    }

    public void open(Player player) {
        var profile = profiles.load(player.getUniqueId());
        HubHolder holder = new HubHolder(
                player.getUniqueId(),
                server,
                messages.component(player, "ui.hub.title")
        );
        Inventory inventory = holder.getInventory();
        inventory.setItem(HOMES, item(
                Material.RED_BED,
                messages.component(player, "ui.hub.homes"),
                List.of(messages.component(
                        player,
                        MessageService.plural("ui.hub.homes-count", profile.homes().size()),
                        Placeholder.unparsed("count", Integer.toString(profile.homes().size()))
                ))
        ));
        inventory.setItem(TELEPORTS, item(
                Material.ENDER_PEARL,
                messages.component(player, "ui.hub.teleports"),
                List.of(messages.component(player, "ui.hub.teleports-description"))
        ));
        inventory.setItem(DEATH, item(
                Material.COMPASS,
                messages.component(player, "ui.hub.death"),
                List.of(messages.component(player, "ui.hub.death-description"))
        ));
        inventory.setItem(STATISTICS, item(
                Material.WRITABLE_BOOK,
                messages.component(player, "ui.hub.statistics"),
                List.of(messages.component(player, "ui.hub.statistics-description"))
        ));
        inventory.setItem(LOCKS, item(
                Material.IRON_DOOR,
                messages.component(player, "ui.hub.locks"),
                List.of(messages.component(player, "ui.hub.locks-description"))
        ));
        inventory.setItem(JOURNEY, item(
                Material.COMPASS,
                messages.component(player, "ui.hub.journey"),
                List.of(messages.component(player, "ui.hub.journey-description"))
        ));
        inventory.setItem(MAIL, item(
                profile.unreadMailCount() > 0 ? Material.WRITABLE_BOOK : Material.BOOK,
                messages.component(player, "ui.hub.mail"),
                List.of(messages.component(
                        player,
                        MessageService.plural("ui.hub.mail-count", profile.unreadMailCount()),
                        Placeholder.unparsed("count", Long.toString(profile.unreadMailCount()))
                ))
        ));
        long unread = notificationService.unread(player.getUniqueId());
        inventory.setItem(NOTIFICATIONS, item(
                unread > 0 ? Material.BELL : Material.PAPER,
                messages.component(player, "ui.hub.notifications"),
                List.of(messages.component(
                        player,
                        MessageService.plural("ui.hub.notifications-count", unread),
                        Placeholder.unparsed("count", Long.toString(unread))
                ))
        ));
        inventory.setItem(HELP, item(
                Material.KNOWLEDGE_BOOK,
                messages.component(player, "ui.hub.help"),
                List.of(messages.component(player, "ui.hub.help-description"))
        ));
        inventory.setItem(PREFERENCES, item(
                Material.COMPARATOR,
                messages.component(player, "ui.hub.preferences"),
                List.of(messages.component(player, "ui.hub.preferences-description"))
        ));
        inventory.setItem(PROFILE, item(
                Material.PLAYER_HEAD,
                messages.component(player, "ui.hub.profile"),
                List.of(messages.component(player, "ui.hub.profile-description"))
        ));
        inventory.setItem(WELCOME_BACK, item(
                Material.WRITTEN_BOOK,
                messages.component(player, "ui.hub.welcome-back"),
                List.of(messages.component(player, "ui.hub.welcome-back-description"))
        ));
        inventory.setItem(CLOSE, item(
                Material.BARRIER,
                messages.component(player, "ui.home-menu.close"),
                List.of()
        ));
        player.openInventory(inventory);
        feedback.play(player, FeedbackService.UI_OPEN);
        onboarding.show(player, OnboardingHint.HUB);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof HubHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        switch (event.getRawSlot()) {
            case HOMES -> homes.open(player, profiles.load(player.getUniqueId()).homes());
            case TELEPORTS -> teleports.open(player);
            case DEATH -> {
                player.closeInventory();
                deathRecovery.openStatus(player);
            }
            case STATISTICS -> statistics.open(player, player);
            case LOCKS -> locks.open(player);
            case JOURNEY -> journey.open(player);
            case MAIL -> mailbox.open(player);
            case NOTIFICATIONS -> notifications.open(player);
            case HELP -> {
                player.closeInventory();
                messages.send(player, "admin.help.header");
                messages.send(player, "ui.hub.help-command");
            }
            case PREFERENCES -> {
                onboarding.show(player, OnboardingHint.LANGUAGE);
                preferences.open(player);
            }
            case PROFILE -> socialProfile.open(player, player);
            case WELCOME_BACK -> welcomeBack.open(player);
            case CLOSE -> player.closeInventory();
            default -> {
                return;
            }
        }
        feedback.play(player, FeedbackService.UI_CLICK);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof HubHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class HubHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Inventory inventory;

        private HubHolder(UUID ownerId, Server server, Component title) {
            this.ownerId = ownerId;
            inventory = server.createInventory(this, 27, title);
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
