package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.object.NotificationType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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

    private final Server server;
    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final TextPromptService prompts;
    private final ConfirmationDialogService dialogs;
    private final Clock clock;
    private final NotificationService notifications;
    private final PlayerExperienceService experience;
    private Consumer<Player> back = Player::closeInventory;

    public LockControlPanelController(
            Server server,
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            FeedbackService feedback,
            TextPromptService prompts,
            ConfirmationDialogService dialogs,
            Clock clock,
            NotificationService notifications,
            PlayerExperienceService experience
    ) {
        this.server = server;
        this.resolver = resolver;
        this.locks = locks;
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
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(panel.viewerId())) {
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
            candidateClick(player, lock, panel, event.getRawSlot());
        } else {
            mainClick(player, lock, panel, event.getRawSlot());
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
                notifyAdministrativeChange(responding, lock, "renamed");
                messages.send(responding, "ui.lock-panel.renamed");
                feedback.play(responding, FeedbackService.LOCK_ACCESS_CHANGED);
                open(responding, lock);
            });
            return;
        }
        if (slot == ACCESS_MODE_SLOT) {
            locks.cycleAccessMode(lock);
            notifyAdministrativeChange(player, lock, "access-mode");
            feedback.play(player, FeedbackService.UI_CLICK);
            openPage(player, lock, panel.page());
            return;
        }
        if (slot == AUTOMATION_SLOT) {
            locks.toggleAutomation(lock);
            notifyAdministrativeChange(player, lock, "automation");
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
            openCandidates(player, lock, CandidateAction.TRUST, 0);
            return;
        }
        if (slot == TRANSFER_SLOT) {
            openCandidates(player, lock, CandidateAction.TRANSFER, 0);
            return;
        }
        if (slot == SEARCH_PLAYER_SLOT) {
            searchPlayer(player, lock, CandidateAction.TRUST);
            return;
        }
        if (slot >= TRUSTED_START && slot <= TRUSTED_END) {
            int index = panel.page() * (TRUSTED_END - TRUSTED_START + 1)
                    + slot - TRUSTED_START;
            List<UUID> trusted = lock.trustedPlayers().stream()
                    .sorted((left, right) -> playerName(left).compareToIgnoreCase(playerName(right)))
                    .toList();
            if (index < trusted.size()) {
                UUID removed = trusted.get(index);
                locks.untrust(lock, removed);
                notifyAdministrativeChange(player, lock, "untrusted:" + playerName(removed));
                messages.send(
                        player,
                        "lock.untrusted",
                        Placeholder.unparsed("player", playerName(removed))
                );
                feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
                openPage(player, lock, panel.page());
            }
        }
    }

    private void candidateClick(Player player, ContainerLock lock, LockPanel panel, int slot) {
        if (slot == CLOSE_SLOT) {
            open(player, lock);
            return;
        }
        if (slot == 45 && panel.page() > 0) {
            openCandidates(player, lock, panel.action(), panel.page() - 1);
            return;
        }
        if (slot == 53 && panel.hasNextPage()) {
            openCandidates(player, lock, panel.action(), panel.page() + 1);
            return;
        }
        if (slot == 50) {
            searchPlayer(player, lock, panel.action());
            return;
        }
        if (slot < 0 || slot >= panel.candidates().size()) {
            return;
        }
        UUID candidateId = panel.candidates().get(slot);
        OfflinePlayer candidate = server.getOfflinePlayer(candidateId);
        String candidateName = candidate.getName() == null
                ? candidateId.toString().substring(0, 8)
                : candidate.getName();
        if (panel.action() == CandidateAction.TRUST) {
            locks.trust(lock, candidateId);
            notifyAdministrativeChange(player, lock, "trusted:" + candidateName);
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
            if (locks.contains(lock)
                    && lock.canManage(
                            player.getUniqueId(),
                            player.hasPermission(LockCommand.ADMIN_PERMISSION)
                    )
                    && locks.transfer(lock, candidateId)) {
                messages.send(
                        player,
                        "ui.lock-panel.transferred",
                        Placeholder.unparsed("player", candidateName)
                );
                notifications.notify(
                        candidateId,
                        NotificationType.LOCK_TRANSFERRED,
                        player.getName(),
                        lock.name()
                );
                feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
                player.closeInventory();
            } else {
                messages.send(player, "lock.not-owner");
            }
        };
        player.closeInventory();
        if (!dialogs.showOwnershipTransfer(player, candidateName, ignored -> transfer.run())) {
            prompts.request(player, "ui.lock-panel.transfer-prompt", (responding, text) -> {
                if (text.equalsIgnoreCase(candidateName)) {
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
            });
        }
    }

    private LockPanel mainPanel(Player player, ContainerLock lock, int page) {
        LockPanel panel = new LockPanel(
                player.getUniqueId(),
                lock.id(),
                null,
                List.of(),
                page,
                false,
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

        List<UUID> trusted = lock.trustedPlayers().stream()
                .sorted((left, right) -> playerName(left).compareToIgnoreCase(playerName(right)))
                .skip((long) page * (TRUSTED_END - TRUSTED_START + 1))
                .limit(TRUSTED_END - TRUSTED_START + 1L)
                .toList();
        for (int index = 0; index < trusted.size(); index++) {
            UUID trustedId = trusted.get(index);
            inventory.setItem(TRUSTED_START + index, playerHead(
                    trustedId,
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
                lock.ownerId(),
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
                                        "ui.lock-panel.mode." + lock.accessMode().name().toLowerCase()
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

    private void openCandidates(Player player, ContainerLock lock, CandidateAction action, int page) {
        List<UUID> allCandidates = java.util.Arrays.stream(server.getOfflinePlayers())
                .filter(candidate -> candidate.hasPlayedBefore() || candidate.isOnline())
                .filter(candidate -> !candidate.getUniqueId().equals(lock.ownerId()))
                .filter(candidate -> action == CandidateAction.TRANSFER
                        || !lock.trustedPlayers().contains(candidate.getUniqueId()))
                .map(OfflinePlayer::getUniqueId)
                .distinct()
                .sorted((left, right) -> playerName(left).compareToIgnoreCase(playerName(right)))
                .toList();
        List<UUID> candidates = allCandidates.stream()
                .skip((long) page * 45)
                .limit(45)
                .toList();
        LockPanel panel = new LockPanel(
                player.getUniqueId(),
                lock.id(),
                action,
                candidates,
                page,
                (page + 1) * 45 < allCandidates.size(),
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
                    candidateId,
                    Component.text(playerName(candidateId)),
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
        if (page > 0) {
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
            if (!locks.contains(lock) || !lock.canManage(
                    responding.getUniqueId(),
                    responding.hasPermission(LockCommand.ADMIN_PERMISSION)
            )) {
                messages.send(responding, "lock.not-owner");
                return;
            }
            OfflinePlayer found = java.util.Arrays.stream(server.getOfflinePlayers())
                    .filter(candidate -> candidate.getName() != null)
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(text.strip()))
                    .findFirst()
                    .orElse(null);
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
                    server,
                    Component.empty()
            );
            candidateClick(responding, lock, synthetic, 0);
        });
    }

    private void notifyAdministrativeChange(Player actor, ContainerLock lock, String detail) {
        if (!actor.getUniqueId().equals(lock.ownerId())) {
            notifications.notify(
                    lock.ownerId(),
                    NotificationType.LOCK_ADMIN_CHANGED,
                    actor.getName(),
                    detail
            );
        }
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

    private ItemStack playerHead(UUID playerId, Component name, List<Component> lore) {
        ItemStack item = item(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(server.getOfflinePlayer(playerId));
        item.setItemMeta(meta);
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

    private String playerName(UUID playerId) {
        OfflinePlayer player = server.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
    }

    private enum CandidateAction {
        TRUST,
        TRANSFER
    }

    private static final class LockPanel implements InventoryHolder {

        private final UUID viewerId;
        private final UUID lockId;
        private final CandidateAction action;
        private final List<UUID> candidates;
        private final int page;
        private final boolean hasNextPage;
        private final Inventory inventory;

        private LockPanel(
                UUID viewerId,
                UUID lockId,
                CandidateAction action,
                List<UUID> candidates,
                int page,
                boolean hasNextPage,
                Server server,
                Component title
        ) {
            this.viewerId = viewerId;
            this.lockId = lockId;
            this.action = action;
            this.candidates = List.copyOf(candidates);
            this.page = page;
            this.hasNextPage = hasNextPage;
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

        private List<UUID> candidates() {
            return candidates;
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
