package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.object.NotificationType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;

import java.util.Set;
import java.time.Clock;
import java.util.UUID;

public final class ContainerLockListener implements Listener {

    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final Clock clock;
    private final NotificationService notifications;
    private final ProfileRepository profiles;

    public ContainerLockListener(
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings,
            Clock clock,
            NotificationService notifications,
            ProfileRepository profiles
    ) {
        this.resolver = resolver;
        this.locks = locks;
        this.messages = messages;
        this.feedback = feedback;
        this.settings = settings;
        this.clock = clock;
        this.notifications = notifications;
        this.profiles = profiles;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Set<BlockKey> blocks = resolver.blocksFor(event.getInventory());
        Set<ContainerLock> selectedLocks = locks.locksFor(blocks);
        boolean allowed = blocks.isEmpty() || hasAccess(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION), selectedLocks);
        selectedLocks.forEach(lock -> locks.recordAccess(
                lock,
                player.getUniqueId(),
                allowed,
                clock.instant()
        ));
        if (!allowed) {
            event.setCancelled(true);
            messages.send(player, "lock.denied-open");
            feedback.play(player, FeedbackService.LOCK_DENIED);
            selectedLocks.forEach(lock -> notifications.notify(
                    lock.ownerId(),
                    NotificationType.LOCK_ACCESS_DENIED,
                    player.getName(),
                    lock.name()
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Set<ContainerLock> selected = locks.locksFor(resolver.blocksFor(event.getView().getTopInventory()));
        if (selected.isEmpty()) {
            return;
        }
        boolean administrator = player.hasPermission(LockCommand.ADMIN_PERMISSION);
        boolean denied = !hasAccess(player.getUniqueId(), administrator, selected);
        boolean withdrawalBlocked = !hasWithdrawAccess(player.getUniqueId(), administrator, selected)
                && !isDepositSafeAction(event);
        if (denied || withdrawalBlocked) {
            event.setCancelled(true);
            messages.send(player, denied ? "lock.denied-open" : "lock.deposit-only");
            feedback.play(player, FeedbackService.LOCK_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Set<ContainerLock> selected = locks.locksFor(resolver.blocksFor(event.getView().getTopInventory()));
        if (selected.isEmpty()) {
            return;
        }
        boolean administrator = player.hasPermission(LockCommand.ADMIN_PERMISSION);
        if (!hasAccess(player.getUniqueId(), administrator, selected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Set<ContainerLock> connectedLocks = locks.locksFor(resolver.blocksFor(event.getBlock()));
        if (connectedLocks.isEmpty()) {
            return;
        }
        if (connectedLocks.size() > 1) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "lock.conflict");
            feedback.play(event.getPlayer(), FeedbackService.LOCK_DENIED);
            return;
        }
        ContainerLock lock = connectedLocks.iterator().next();

        Player player = event.getPlayer();
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            event.setCancelled(true);
            messages.send(player, "lock.denied-break");
            feedback.play(player, FeedbackService.LOCK_DENIED);
            return;
        }

        if (!player.getUniqueId().equals(lock.ownerId())) {
            notifications.notify(
                    lock.ownerId(),
                    NotificationType.LOCK_ADMIN_CHANGED,
                    player.getName(),
                    "removed-by-break"
            );
        }
        locks.remove(lock);
        messages.send(player, "lock.removed-by-break");
        feedback.play(player, FeedbackService.LOCK_REMOVED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Set<BlockKey> connected = resolver.blocksFor(event.getBlockPlaced());
        Set<ContainerLock> existing = locks.locksFor(connected);
        if (existing.isEmpty()) {
            return;
        }
        if (existing.size() > 1) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "lock.conflict");
            feedback.play(event.getPlayer(), FeedbackService.LOCK_DENIED);
            return;
        }

        ContainerLock lock = existing.iterator().next();
        Player player = event.getPlayer();
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            event.setCancelled(true);
            messages.send(player, "lock.denied-attach");
            feedback.play(player, FeedbackService.LOCK_DENIED);
            return;
        }

        if (locks.addBlocks(lock, connected)) {
            if (!player.getUniqueId().equals(lock.ownerId())) {
                notifications.notify(
                        lock.ownerId(),
                        NotificationType.LOCK_ADMIN_CHANGED,
                        player.getName(),
                        "extended"
                );
            }
            messages.send(player, "lock.extended");
            feedback.play(player, FeedbackService.LOCK_CREATED);
        } else {
            event.setCancelled(true);
            messages.send(player, "lock.conflict");
            feedback.play(player, FeedbackService.LOCK_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (settings.current().protectLocksFromExplosions()) {
            protect(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (settings.current().protectLocksFromExplosions()) {
            protect(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!settings.current().blockLockedContainerAutomation()) {
            return;
        }
        if (locks.lockCount() == 0) {
            return;
        }
        ContainerBlockResolver.BlockPair source = resolver.blockPairFor(event.getSource());
        ContainerBlockResolver.BlockPair destination = resolver.blockPairFor(event.getDestination());
        if (!locks.automationAllowed(source.first(), source.second())
                || !locks.automationAllowed(destination.first(), destination.second())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (settings.current().blockLockedContainerAutomation()
                && locks.lockCount() > 0) {
            ContainerBlockResolver.BlockPair blocks = resolver.blockPairFor(event.getInventory());
            if (!locks.automationAllowed(blocks.first(), blocks.second())) {
                event.setCancelled(true);
            }
        }
    }

    private void protect(java.util.List<Block> blocks) {
        blocks.removeIf(this::isProtectedBlock);
    }

    private boolean isDepositSafeAction(InventoryClickEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= 0 && event.getRawSlot() < topSize) {
            return event.getAction() == InventoryAction.PLACE_ALL
                    || event.getAction() == InventoryAction.PLACE_ONE
                    || event.getAction() == InventoryAction.PLACE_SOME;
        }
        if (event.getRawSlot() >= topSize) {
            return event.getAction() != InventoryAction.COLLECT_TO_CURSOR;
        }
        return true;
    }

    private boolean isProtectedBlock(Block block) {
        if (locks.lockCount() == 0) {
            return false;
        }
        Set<BlockKey> connected = resolver.blocksFor(block);
        return connected.isEmpty()
                ? locks.isLocked(BlockKey.from(block))
                : !locks.locksFor(connected).isEmpty();
    }

    private boolean hasAccess(UUID playerId, boolean administrator, Set<ContainerLock> selectedLocks) {
        return selectedLocks.stream().allMatch(lock ->
                lock.canAccess(playerId, administrator) || profiles.isTrusted(lock.ownerId(), playerId));
    }

    private boolean hasWithdrawAccess(UUID playerId, boolean administrator, Set<ContainerLock> selectedLocks) {
        return selectedLocks.stream().allMatch(lock ->
                lock.canWithdraw(playerId, administrator) || profiles.isTrusted(lock.ownerId(), playerId));
    }
}
