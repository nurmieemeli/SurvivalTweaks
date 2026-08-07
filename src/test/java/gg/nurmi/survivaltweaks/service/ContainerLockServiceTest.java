package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import gg.nurmi.survivaltweaks.storage.ContainerLockStore;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerLockServiceTest {

    @TempDir
    Path directory;

    @Test
    void rejectsOverlapsAndPersistsTheLatestAclAndBlockSet() {
        Logger logger = Logger.getLogger(ContainerLockServiceTest.class.getName());
        ContainerLockStore store = new ContainerLockStore(directory.resolve("locks.yml"), logger);
        ContainerLockService service = new ContainerLockService(store, logger);
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        BlockKey left = block(10);
        BlockKey right = new BlockKey(left.worldId(), 11, left.y(), left.z());

        ContainerLock lock = service.create(owner, Set.of(left)).orElseThrow();
        assertTrue(service.create(UUID.randomUUID(), Set.of(left)).isEmpty());
        assertTrue(service.addBlocks(lock, Set.of(left, right)));
        assertTrue(service.trust(lock, trusted));
        assertTrue(service.rename(lock, "Workshop supplies"));
        assertTrue(service.cycleAccessMode(lock));
        assertTrue(service.cycleAccessMode(lock));
        assertTrue(service.canAccess(trusted, false, Set.of(right)));
        assertTrue(service.canAccess(UUID.randomUUID(), false, Set.of(left)));
        service.close();

        ContainerLockService reloaded = new ContainerLockService(store, logger);
        ContainerLock loaded = reloaded.lockFor(right).orElseThrow();
        assertEquals(owner, loaded.ownerId());
        assertEquals("Workshop supplies", loaded.name());
        assertEquals(LockAccessMode.PUBLIC, loaded.accessMode());
        assertTrue(loaded.trustedPlayers().contains(trusted));
        assertEquals(2, loaded.blocks().size());
        reloaded.close();
    }

    @Test
    void ownershipTransferPersistsAndRecentAccessIsBoundedNewestFirst() {
        Logger logger = Logger.getLogger(ContainerLockServiceTest.class.getName());
        ContainerLockStore store = new ContainerLockStore(directory.resolve("transfer.yml"), logger);
        ContainerLockService service = new ContainerLockService(store, logger);
        UUID originalOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        ContainerLock lock = service.create(originalOwner, Set.of(block(30))).orElseThrow();

        assertTrue(service.transfer(lock, newOwner));
        for (int index = 0; index < 7; index++) {
            service.recordAccess(lock, UUID.randomUUID(), index % 2 == 0, Instant.ofEpochSecond(index));
        }

        assertEquals(5, service.recentAccess(lock).size());
        assertEquals(Instant.ofEpochSecond(6), service.recentAccess(lock).getFirst().when());
        assertEquals(Instant.ofEpochSecond(2), service.recentAccess(lock).getLast().when());
        service.close();

        ContainerLockService reloaded = new ContainerLockService(store, logger);
        ContainerLock loaded = reloaded.lockFor(lock.blocks().iterator().next()).orElseThrow();
        assertEquals(newOwner, loaded.ownerId());
        assertTrue(loaded.trustedPlayers().contains(originalOwner));
        reloaded.close();
    }

    @Test
    void allocationFreeAutomationCheckCoversBothBlocksAndRemovalClearsHistory() {
        Logger logger = Logger.getLogger(ContainerLockServiceTest.class.getName());
        ContainerLockService service = new ContainerLockService(
                new ContainerLockStore(directory.resolve("automation.yml"), logger),
                logger
        );
        BlockKey first = block(60);
        BlockKey second = block(61);
        ContainerLock lock = service.create(UUID.randomUUID(), Set.of(first, second)).orElseThrow();
        service.recordAccess(lock, UUID.randomUUID(), false, Instant.EPOCH);

        assertFalse(service.automationAllowed(first, null));
        assertFalse(service.automationAllowed(null, second));
        assertTrue(service.toggleAutomation(lock));
        assertTrue(service.automationAllowed(first, second));

        service.remove(lock);
        assertTrue(service.recentAccess(lock).isEmpty());
        service.close();
    }

    private BlockKey block(int x) {
        return new BlockKey(UUID.randomUUID(), x, 64, 20);
    }
}
