package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.storage.ContainerLockDataStore;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerLockListenerTest {

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
