package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.storage.ContainerLockDataStore;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLockListenerTest {

    @Test
    void hopperInventoryLookupHonorsTheAutomationPolicy() {
        var resolver = mock(ContainerBlockResolver.class);
        var hopper = mock(Block.class);
        var chest = mock(Block.class);
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(hopper.getWorld()).thenReturn(world);
        when(chest.getWorld()).thenReturn(world);
        when(chest.getY()).thenReturn(1);
        var inventory = mock(Inventory.class);
        var key = BlockKey.from(chest);
        when(resolver.blockPairFor(inventory)).thenReturn(new ContainerBlockResolver.BlockPair(key, null));
        var configuration = mock(PluginSettings.class);
        when(configuration.blockLockedContainerAutomation()).thenReturn(true);
        try (var locks = new ContainerLockService(mock(ContainerLockDataStore.class), Logger.getAnonymousLogger())) {
            var lock = locks.create(UUID.randomUUID(), Set.of(key)).orElseThrow();
            var listener = new ContainerLockListener(resolver, locks, null, null, new SettingsService(configuration),
                    Clock.systemUTC(), null, null, null);
            var blocked = new HopperInventorySearchEvent(inventory, HopperInventorySearchEvent.ContainerType.SOURCE,
                    hopper, chest);
            listener.onHopperInventorySearch(blocked);
            assertEquals(null, blocked.getInventory());

            locks.toggleAutomation(lock);
            var allowed = new HopperInventorySearchEvent(inventory, HopperInventorySearchEvent.ContainerType.SOURCE,
                    hopper, chest);
            listener.onHopperInventorySearch(allowed);
            assertEquals(inventory, allowed.getInventory());
        }
    }

    @Test
    void entityBlockDestructionCannotRemoveALockedContainer() {
        var resolver = mock(ContainerBlockResolver.class);
        var block = mock(Block.class);
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.CHEST);
        var event = mock(EntityChangeBlockEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getTo()).thenReturn(Material.AIR);
        try (var locks = new ContainerLockService(mock(ContainerLockDataStore.class), Logger.getAnonymousLogger())) {
            var listener = new ContainerLockListener(resolver, locks, null, null, null,
                    Clock.systemUTC(), null, null, null);
            listener.onEntityChangeBlock(event);
            verify(event, never()).setCancelled(true);
            locks.create(UUID.randomUUID(), Set.of(BlockKey.from(block))).orElseThrow();

            listener.onEntityChangeBlock(event);
            verify(event).setCancelled(true);
        }
    }

    @Test
    void redstoneCannotUseALockedBlockUnlessAutomationIsAllowed() {
        var world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        var configuration = mock(PluginSettings.class);
        when(configuration.blockLockedContainerAutomation()).thenReturn(true);
        var settings = new SettingsService(configuration);
        try (var locks = new ContainerLockService(mock(ContainerLockDataStore.class), Logger.getAnonymousLogger())) {
            var lock = locks.create(UUID.randomUUID(), Set.of(BlockKey.from(block))).orElseThrow();
            var listener = new ContainerLockListener(null, locks, null, null, settings,
                    Clock.systemUTC(), null, null, null);
            var blocked = mock(BlockDispenseEvent.class);
            when(blocked.getBlock()).thenReturn(block);
            listener.onDispense(blocked);
            verify(blocked).setCancelled(true);
            var craft = mock(CrafterCraftEvent.class);
            when(craft.getBlock()).thenReturn(block);
            listener.onCraft(craft);
            verify(craft).setCancelled(true);

            locks.toggleAutomation(lock);
            var allowed = mock(BlockDispenseEvent.class);
            when(allowed.getBlock()).thenReturn(block);
            listener.onDispense(allowed);
            verify(allowed, never()).setCancelled(true);
        }
    }

    @Test
    void appliesLockChangesOnlyInTheAcceptedEventPhase() throws Exception {
        ContainerBlockResolver resolver = mock(ContainerBlockResolver.class);
        Player owner = mock(Player.class);
        UUID ownerId = UUID.randomUUID();
        when(owner.getUniqueId()).thenReturn(ownerId);
        UUID world = UUID.randomUUID();
        BlockKey original = new BlockKey(world, 1, 64, 1);
        BlockKey adjacent = new BlockKey(world, 2, 64, 1);
        Block block = mock(Block.class);
        World blockWorld = mock(World.class);
        when(blockWorld.getUID()).thenReturn(world);
        when(block.getWorld()).thenReturn(blockWorld);
        when(block.getX()).thenReturn(1);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(1);
        when(resolver.blocksFor(block)).thenReturn(Set.of(original, adjacent));
        BlockBreakEvent breaking = new BlockBreakEvent(block, owner);
        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getBlockPlaced()).thenReturn(block);
        when(placing.getPlayer()).thenReturn(owner);

        try (ContainerLockService locks = new ContainerLockService(
                mock(ContainerLockDataStore.class), Logger.getAnonymousLogger())) {
            var lock = locks.create(ownerId, Set.of(original)).orElseThrow();
            var listener = new ContainerLockListener(resolver, locks, mock(MessageService.class),
                    mock(FeedbackService.class), null, Clock.systemUTC(), null, null, null);

            listener.onPlace(placing);
            assertFalse(locks.lockFor(adjacent).isPresent());
            listener.onAcceptedPlace(placing);
            assertFalse(locks.lockFor(adjacent).isPresent());
            when(placing.canBuild()).thenReturn(true);
            listener.onAcceptedPlace(placing);
            assertEquals(lock, locks.lockFor(adjacent).orElseThrow());

            listener.onBreak(breaking);
            assertTrue(locks.contains(lock));
            assertFalse(breaking.isCancelled());
            listener.onAcceptedBreak(breaking);
            assertTrue(locks.contains(lock));
            assertFalse(locks.isLocked(original));
            assertEquals(lock, locks.lockFor(adjacent).orElseThrow());

            Block survivor = mock(Block.class);
            when(survivor.getWorld()).thenReturn(blockWorld);
            when(survivor.getX()).thenReturn(2);
            when(survivor.getY()).thenReturn(64);
            when(survivor.getZ()).thenReturn(1);
            when(resolver.blocksFor(survivor)).thenReturn(Set.of(adjacent));
            listener.onAcceptedBreak(new BlockBreakEvent(survivor, owner));
            assertFalse(locks.contains(lock));
        }

        assertMonitor("onAcceptedBreak", BlockBreakEvent.class);
        assertMonitor("onAcceptedPlace", BlockPlaceEvent.class);
    }

    private static void assertMonitor(String handler, Class<?> event) throws NoSuchMethodException {
        EventHandler annotation = ContainerLockListener.class.getMethod(handler, event).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }
}
