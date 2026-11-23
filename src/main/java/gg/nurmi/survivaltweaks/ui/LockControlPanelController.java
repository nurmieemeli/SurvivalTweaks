package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerNameLookup;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;

import static gg.nurmi.survivaltweaks.ui.MenuItems.item;
import static gg.nurmi.survivaltweaks.ui.MenuItems.playerHead;

public final class LockControlPanelController implements Listener {

    private static final int RENAME_SLOT = 4;
    private static final int ADD_TRUSTED_SLOT = 29;
    private static final int SEARCH_PLAYER_SLOT = 28;
    private static final int TRANSFER_SLOT = 31;
    private static final int AUTOMATION_SLOT = 32;
    private static final int ACCESS_MODE_SLOT = 33;
    private static final int CLOSE_SLOT = 49;
    private static final int PREVIOUS_SLOT = 27;
    private static final int NEXT_SLOT = 35;
    private static final int TRUSTED_START = 9;
    private static final int TRUSTED_END = 26;
    private static final int HISTORY_START = 36;
    private static final int CANDIDATE_PAGE_SIZE = 45;
    private static final int ROSTER_BATCH_SIZE = 32;

    private final JavaPlugin plugin;
    private final Server server;
    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final SettingsService settings;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final TextPromptService prompts;
    private final ConfirmationDialogService dialogs;
    private final Clock clock;
    private final NotificationService notifications;
    private final PlayerExperienceService experience;
    private Consumer<Player> back = Player::closeInventory;
    private long transferConfirmation = ThreadLocalRandom.current().nextLong();

    public LockControlPanelController(
            JavaPlugin plugin,
            Server server,
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            TextPromptService prompts,
            ConfirmationDialogService dialogs,
            Clock clock,
            NotificationService notifications,
            PlayerExperienceService experience
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.server = server;
        this.resolver = resolver;
        this.locks = locks;
        this.settings = settings;
        this.messages = messages;
        this.feedback = feedback;
        this.prompts = prompts;
        this.dialogs = dialogs;
        this.clock = clock;
        this.notifications = notifications;
        this.experience = experience;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void open(Player player, ContainerLock lock) {
        if (!messages.requirePermission(player, "survivaltweaks.command.lock")) {
            return;
        }
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            messages.send(player, "lock.not-owner");
            return;
        }
        player.openInventory(mainPanel(player, lock, 0).getInventory());
        outline(player, lock);
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    private void openPage(Player player, ContainerLock lock, int page) {
        player.openInventory(mainPanel(player, lock, Math.max(0, page)).getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !event.getPlayer().isSneaking()
                || event.getClickedBlock() == null) {
            return;
        }
        Set<ContainerLock> found = locks.locksFor(resolver.blocksFor(event.getClickedBlock()));
        if (found.size() != 1) {
            return;
        }
        ContainerLock lock = found.iterator().next();
        Player player = event.getPlayer();
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            return;
        }
        event.setCancelled(true);
        open(player, lock);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof LockPanel panel)) {
            return;
        }
        SurvivalTweaksMenu.afterClick(plugin, event, panel.viewerId(),
                (player, slot) -> handleClick(player, panel, slot));
    }

    private void handleClick(Player player, LockPanel panel, int slot) {
        if (!messages.requirePermission(player, "survivaltweaks.command.lock")) {
            return;
        }
        Optional<ContainerLock> selected = locks.lock(panel.lockId());
        if (selected.isEmpty()) {
            player.closeInventory();
            messages.send(player, "lock.not-locked");
            return;
        }
        ContainerLock lock = selected.orElseThrow();
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            player.closeInventory();
            messages.send(player, "lock.not-owner");
            return;
        }
        if (panel.action() != null) {
            candidateClick(player, lock, panel, slot);
        } else {
            mainClick(player, lock, panel, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof LockPanel) {
            event.setCancelled(true);
        }
    }

    private void mainClick(Player player, ContainerLock lock, LockPanel panel, int slot) {
        if (slot == CLOSE_SLOT) {
            back.accept(player);
            return;
        }
        if (slot == RENAME_SLOT) {
            prompts.request(player, "ui.lock-panel.rename-prompt", (responding, text) -> {
                if (!messages.requirePermission(responding, "survivaltweaks.command.lock")) {
                    return;
                }
                if (!locks.contains(lock) || !lock.canManage(
                        responding.getUniqueId(),
                        responding.hasPermission(LockCommand.ADMIN_PERMISSION)
                )) {
                    messages.send(responding, "lock.not-owner");
                    return;
                }
                if (text.length() > 32) {
                    messages.send(responding, "ui.text-prompt.too-long");
                    return;
                }
                locks.rename(lock, text.equals("-") ? "" : text);
                notifications.lockAdministrativeChange(lock.ownerId(), responding.getUniqueId(), responding.getName(), "renamed");
                messages.send(responding, "ui.lock-panel.renamed");
                feedback.play(responding, FeedbackService.LOCK_ACCESS_CHANGED);
                open(responding, lock);
            });
            return;
        }
        if (slot == ACCESS_MODE_SLOT) {
            locks.cycleAccessMode(lock);
            notifications.lockAdministrativeChange(lock.ownerId(), player.getUniqueId(), player.getName(), "access-mode");
            feedback.play(player, FeedbackService.UI_CLICK);
            openPage(player, lock, panel.page());
            return;
        }
        if (slot == AUTOMATION_SLOT) {
            locks.toggleAutomation(lock);
            notifications.lockAdministrativeChange(lock.ownerId(), player.getUniqueId(), player.getName(), "automation");
            feedback.play(player, FeedbackService.UI_CLICK);
            openPage(player, lock, panel.page());
            return;
        }
        if (slot == PREVIOUS_SLOT && panel.page() > 0) {
            openPage(player, lock, panel.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT
                && (panel.page() + 1) * (TRUSTED_END - TRUSTED_START + 1)
                < lock.trustedPlayers().size()) {
            openPage(player, lock, panel.page() + 1);
            return;
        }
        if (slot == ADD_TRUSTED_SLOT) {
            openCandidates(player, lock, panel, CandidateAction.TRUST);
            return;
        }
        if (slot == TRANSFER_SLOT) {
            openCandidates(player, lock, panel, CandidateAction.TRANSFER);
            return;
        }
        if (slot == SEARCH_PLAYER_SLOT) {
            searchPlayer(player, lock, CandidateAction.TRUST);
            return;
        }
        if (slot >= TRUSTED_START && slot <= TRUSTED_END) {
            int index = slot - TRUSTED_START;
            if (index < panel.playerIds().size()) {
                UUID removed = panel.playerIds().get(index);
                if (locks.untrust(lock, removed)) {
                    String name = playerName(removed);
                    notifications.lockAdministrativeChange(lock.ownerId(), player.getUniqueId(), player.getName(), "untrusted:" + name);
                    messages.send(
                            player,
                            "lock.untrusted",
                            Placeholder.unparsed("player", name)
                    );
                    feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
                }
                openPage(player, lock, panel.page());
            }
        }
    }

    private void candidateClick(Player player, ContainerLock lock, LockPanel panel, int slot) {
        if (!messages.requirePermission(player, "survivaltweaks.command.lock")) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            open(player, lock);
            return;
        }
        if (slot == 45 && panel.page() > 0) {
            showCandidates(player, lock, panel.action(), panel.page() - 1, panel.roster);
            return;
        }
        if (slot == 53 && panel.hasNextPage()) {
            showCandidates(player, lock, panel.action(), panel.page() + 1, panel.roster);
            return;
        }
        if (slot == 50) {
            searchPlayer(player, lock, panel.action());
            return;
        }
        if (slot < 0 || slot >= panel.playerIds().size()) {
            return;
        }
        UUID candidateId = panel.playerIds().get(slot);
        OfflinePlayer candidate = server.getOfflinePlayer(candidateId);
        String candidateName = candidate.getName() == null
                ? candidateId.toString().substring(0, 8)
                : candidate.getName();
        if (panel.action() == CandidateAction.TRUST) {
            if (!locks.trust(lock, candidateId)) {
                open(player, lock);
                return;
            }
            notifications.lockAdministrativeChange(lock.ownerId(), player.getUniqueId(), player.getName(), "trusted:" + candidateName);
            messages.send(
                    player,
                    "lock.trusted",
                    Placeholder.unparsed("player", candidateName)
            );
            feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
            open(player, lock);
            return;
        }
        Runnable transfer = () -> {
            if (!messages.requirePermission(player, "survivaltweaks.command.lock")) {
                return;
            }
            if (!locks.contains(lock)
                    || !lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
                messages.send(player, "lock.not-owner");
                return;
            }
            UUID previousOwnerId = lock.ownerId();
            ContainerLockService.TransferResult result = locks.transfer(
                    lock, candidateId, settings.current().maxLocksPerPlayer());
            if (result == ContainerLockService.TransferResult.LIMIT) {
                messages.send(player, "lock.transfer-limit", Placeholder.unparsed("player", candidateName));
            } else if (result == ContainerLockService.TransferResult.TRANSFERRED) {
                messages.send(
                        player,
                        "ui.lock-panel.transferred",
                        Placeholder.unparsed("player", candidateName)
                );
                notifications.lockTransferred(previousOwnerId, candidateId, player.getUniqueId(),
                        player.getName(), candidateName, lock.name());
                feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
                player.closeInventory();
            } else {
                messages.send(player, "lock.already-owner");
            }
        };
        player.closeInventory();
        if (!dialogs.showOwnershipTransfer(player, candidateName, ignored -> transfer.run())) {
            String code = Long.toUnsignedString(++transferConfirmation, 36);
            prompts.request(player, "ui.lock-panel.transfer-code-prompt", (responding, text) -> {
                if (text.equalsIgnoreCase(code)) {
                    transfer.run();
                    return;
                }
                messages.send(responding, "ui.lock-panel.transfer-cancelled");
                if (locks.contains(lock) && lock.canManage(
                        responding.getUniqueId(),
                        responding.hasPermission(LockCommand.ADMIN_PERMISSION)
                )) {
                    open(responding, lock);
                }
            }, Placeholder.unparsed("player", candidateName), Placeholder.unparsed("code", code));
        }
    }

    private LockPanel mainPanel(Player player, ContainerLock lock, int page) {
        List<UUID> trusted = lock.trustedPlayers().stream()
                .sorted((left, right) -> playerName(left).compareToIgnoreCase(playerName(right)))
                .skip((long) page * (TRUSTED_END - TRUSTED_START + 1))
                .limit(TRUSTED_END - TRUSTED_START + 1L)
                .toList();
        LockPanel panel = new LockPanel(
                player.getUniqueId(),
                lock.id(),
                null,
                trusted,
                page,
                false,
                null,
                server,
                messages.component(player, "ui.lock-panel.title")
        );
        Inventory inventory = panel.getInventory();
        String displayName = lock.name().isBlank()
                ? messages.plain(player, "ui.lock-panel.default-name")
                : lock.name();
        inventory.setItem(RENAME_SLOT, item(
                Material.NAME_TAG,
                messages.component(
                        player,
                        "ui.lock-panel.name",
                        Placeholder.unparsed("name", displayName)
                ),
                List.of(messages.component(player, "ui.lock-panel.rename-action"))
        ));

        for (int index = 0; index < trusted.size(); index++) {
            UUID trustedId = trusted.get(index);
            inventory.setItem(TRUSTED_START + index, playerHead(
                    server.getOfflinePlayer(trustedId),
                    messages.component(
                            player,
                            "ui.lock-panel.trusted-player",
                            Placeholder.unparsed("player", playerName(trustedId))
                    ),
                    List.of(messages.component(player, "ui.lock-panel.remove-trusted"))
            ));
        }
        inventory.setItem(ADD_TRUSTED_SLOT, item(
                Material.EMERALD,
                messages.component(player, "ui.lock-panel.add-trusted"),
                List.of()
        ));
        inventory.setItem(SEARCH_PLAYER_SLOT, item(
                Material.SPYGLASS,
                messages.component(player, "ui.lock-panel.search-player"),
                List.of()
        ));
        inventory.setItem(TRANSFER_SLOT, playerHead(
                server.getOfflinePlayer(lock.ownerId()),
                messages.component(
                        player,
                        "ui.lock-panel.owner",
                        Placeholder.unparsed("player", playerName(lock.ownerId()))
                ),
                List.of(messages.component(player, "ui.lock-panel.transfer-action"))
        ));
        inventory.setItem(ACCESS_MODE_SLOT, item(
                switch (lock.accessMode()) {
                    case PUBLIC -> Material.OAK_DOOR;
                    case DEPOSIT_ONLY -> Material.HOPPER;
                    case TRUSTED -> Material.IRON_DOOR;
                },
                messages.component(
                        player,
                        "ui.lock-panel.access-mode",
                        Placeholder.component(
                                "mode",
                                messages.component(
                                        player,
                                        "ui.lock-panel.mode." + lock.accessMode().name().toLowerCase(Locale.ROOT)
                                )
                        )
                ),
                List.of(messages.component(player, "ui.lock-panel.mode-action"))
        ));
        inventory.setItem(AUTOMATION_SLOT, item(
                lock.automationAllowed() ? Material.HOPPER : Material.BARRIER,
                messages.component(
                        player,
                        lock.automationAllowed()
                                ? "ui.lock-panel.automation-enabled"
                                : "ui.lock-panel.automation-disabled"
                ),
                List.of(messages.component(player, "ui.lock-panel.automation-action"))
        ));
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.previous"),
                    List.of()
            ));
        }
        if ((page + 1) * (TRUSTED_END - TRUSTED_START + 1) < lock.trustedPlayers().size()) {
            inventory.setItem(NEXT_SLOT, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.next"),
                    List.of()
            ));
        }
        List<ContainerLockService.AccessAttempt> history = locks.recentAccess(lock);
        for (int index = 0; index < history.size(); index++) {
            ContainerLockService.AccessAttempt attempt = history.get(index);
            long seconds = Math.max(0L, Duration.between(attempt.when(), clock.instant()).toSeconds());
            inventory.setItem(HISTORY_START + index, item(
                    attempt.allowed() ? Material.LIME_DYE : Material.RED_DYE,
                    messages.component(
                            player,
                            attempt.allowed()
                                    ? "ui.lock-panel.access-allowed"
                                    : "ui.lock-panel.access-denied",
                            Placeholder.unparsed("player", playerName(attempt.playerId())),
                            Placeholder.unparsed("seconds", Long.toString(seconds))
                    ),
                    List.of()
            ));
        }
        inventory.setItem(CLOSE_SLOT, item(
                Material.BARRIER,
                messages.component(player, "ui.home-menu.close"),
                List.of()
        ));
        return panel;
    }

    private void openCandidates(Player player, ContainerLock lock, LockPanel origin, CandidateAction action) {
        RosterRequest request = new RosterRequest(player, lock, origin, action);
        origin.rosterRequest = request;
        request.collectBatch();
    }

    private final class RosterRequest {
        private final Player player;
        private final ContainerLock lock;
        private final LockPanel origin;
        private final CandidateAction action;
        private final OfflinePlayer[] offlinePlayers;
        private final Map<UUID, String> names = new LinkedHashMap<>();
        private int index;

        private RosterRequest(Player player, ContainerLock lock, LockPanel origin, CandidateAction action) {
            this.player = player;
            this.lock = lock;
            this.origin = origin;
            this.action = action;
            this.offlinePlayers = server.getOfflinePlayers();
        }

        private boolean isCurrent() {
            return origin.rosterRequest == this
                    && player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder(false) == origin
                    && locks.contains(lock)
                    && player.hasPermission("survivaltweaks.command.lock")
                    && lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION));
        }

        private void collectBatch() {
            if (!isCurrent()) {
                return;
            }
            int end = Math.min(index + ROSTER_BATCH_SIZE, offlinePlayers.length);
            while (index < end) {
                OfflinePlayer candidate = offlinePlayers[index++];
                if (candidate.isOnline() || candidate.hasPlayedBefore()) {
                    UUID id = candidate.getUniqueId();
                    String name = candidate.getName();
                    names.putIfAbsent(id, name == null ? id.toString().substring(0, 8) : name);
                }
            }
            if (index < offlinePlayers.length) {
                server.getScheduler().runTaskLater(plugin, this::collectBatch, 1L);
                return;
            }
            server.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<UUID> sorted = names.keySet().stream()
                        .sorted((left, right) -> names.get(left).compareToIgnoreCase(names.get(right)))
                        .toList();
                CandidateRoster roster = new CandidateRoster(sorted, Map.copyOf(names));
                server.getScheduler().runTask(plugin, () -> {
                    if (isCurrent()) {
                        origin.rosterRequest = null;
                        showCandidates(player, lock, action, 0, roster);
                    }
                });
            });
        }
    }

    private record CandidateRoster(List<UUID> sortedIds, Map<UUID, String> names) {
    }

    private void showCandidates(
            Player player,
            ContainerLock lock,
            CandidateAction action,
            int page,
            CandidateRoster roster
    ) {
        UUID ownerId = lock.ownerId();
        Set<UUID> alreadyTrusted = action == CandidateAction.TRUST ? lock.trustedPlayers() : Set.of();
        List<UUID> eligible = roster.sortedIds().stream()
                .filter(id -> !id.equals(ownerId) && !alreadyTrusted.contains(id))
                .toList();
        int visiblePage = Math.min(page, Math.max(0, eligible.size() - 1) / CANDIDATE_PAGE_SIZE);
        int start = visiblePage * CANDIDATE_PAGE_SIZE;
        int end = Math.min(start + CANDIDATE_PAGE_SIZE, eligible.size());
        List<UUID> candidates = eligible.subList(start, end);
        LockPanel panel = new LockPanel(
                player.getUniqueId(),
                lock.id(),
                action,
                candidates,
                visiblePage,
                end < eligible.size(),
                roster,
                server,
                messages.component(
                        player,
                        action == CandidateAction.TRUST
                                ? "ui.lock-panel.select-trusted"
                                : "ui.lock-panel.select-owner"
                )
        );
        for (int index = 0; index < candidates.size(); index++) {
            UUID candidateId = candidates.get(index);
            panel.getInventory().setItem(index, playerHead(
                    server.getOfflinePlayer(candidateId),
                    Component.text(roster.names().get(candidateId)),
                    List.of()
            ));
        }
        panel.getInventory().setItem(CLOSE_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.lock-panel.back"),
                List.of()
        ));
        panel.getInventory().setItem(50, item(
                Material.SPYGLASS,
                messages.component(player, "ui.lock-panel.search-player"),
                List.of()
        ));
        if (visiblePage > 0) {
            panel.getInventory().setItem(45, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.previous"),
                    List.of()
            ));
        }
        if (panel.hasNextPage()) {
            panel.getInventory().setItem(53, item(
                    Material.ARROW,
                    messages.component(player, "ui.common.next"),
                    List.of()
            ));
        }
        player.openInventory(panel.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    private void searchPlayer(Player player, ContainerLock lock, CandidateAction action) {
        prompts.request(player, "ui.lock-panel.search-prompt", (responding, text) -> {
            if (!messages.requirePermission(responding, "survivaltweaks.command.lock")) {
                return;
            }
            if (!locks.contains(lock) || !lock.canManage(
                    responding.getUniqueId(),
                    responding.hasPermission(LockCommand.ADMIN_PERMISSION)
            )) {
                messages.send(responding, "lock.not-owner");
                return;
            }
            // Resolving the typed name directly beats scanning every player who
            // has ever joined, and unlike getOfflinePlayer(String) it neither
            // blocks on Mojang's API nor invents a profile for an unknown name.
            OfflinePlayer found = PlayerNameLookup.findCached(server, text.strip());
            if (found == null
                    || found.getUniqueId().equals(lock.ownerId())
                    || (action == CandidateAction.TRUST
                    && lock.trustedPlayers().contains(found.getUniqueId()))) {
                messages.send(responding, "ui.lock-panel.player-not-found");
                open(responding, lock);
                return;
            }
            LockPanel synthetic = new LockPanel(
                    responding.getUniqueId(),
                    lock.id(),
                    action,
                    List.of(found.getUniqueId()),
                    0,
                    false,
                    null,
                    server,
                    Component.empty()
            );
            candidateClick(responding, lock, synthetic, 0);
        });
    }

    private void outline(Player player, ContainerLock lock) {
        var preferences = experience.preferences(player);
        if (!preferences.particlesEnabled()) {
            return;
        }
        int count = preferences.reducedEffects() ? 5 : 18;
        lock.blocks().forEach(block -> {
            World world = server.getWorld(block.worldId());
            if (world != null && world.getUID().equals(player.getWorld().getUID())) {
                player.spawnParticle(
                        Particle.WAX_ON,
                        block.x() + 0.5,
                        block.y() + 0.6,
                        block.z() + 0.5,
                        count,
                        0.45,
                        0.45,
                        0.45,
                        0
                );
            }
        });
    }

    private String playerName(UUID playerId) {
        return PlayerNameLookup.displayName(server, playerId);
    }

    private enum CandidateAction {
        TRUST,
        TRANSFER
    }

    private static final class LockPanel implements SurvivalTweaksMenu {

        private final UUID viewerId;
        private final UUID lockId;
        private final CandidateAction action;
        private final List<UUID> playerIds;
        private final int page;
        private final boolean hasNextPage;
        private final CandidateRoster roster;
        private final Inventory inventory;
        private RosterRequest rosterRequest;

        private LockPanel(
                UUID viewerId,
                UUID lockId,
                CandidateAction action,
                List<UUID> playerIds,
                int page,
                boolean hasNextPage,
                CandidateRoster roster,
                Server server,
                Component title
        ) {
            this.viewerId = viewerId;
            this.lockId = lockId;
            this.action = action;
            this.playerIds = List.copyOf(playerIds);
            this.page = page;
            this.hasNextPage = hasNextPage;
            this.roster = roster;
            this.inventory = server.createInventory(this, 54, title);
        }

        private UUID viewerId() {
            return viewerId;
        }

        private UUID lockId() {
            return lockId;
        }

        private CandidateAction action() {
            return action;
        }

        private List<UUID> playerIds() {
            return playerIds;
        }

        private int page() {
            return page;
        }

        private boolean hasNextPage() {
            return hasNextPage;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
