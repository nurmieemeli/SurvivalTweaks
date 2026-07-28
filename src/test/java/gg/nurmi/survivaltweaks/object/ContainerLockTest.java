package gg.nurmi.survivaltweaks.object;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockTest {

    @Test
    void ownerTrustedPlayerAndAdministratorHaveExpectedAccess() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ContainerLock lock = ContainerLock.create(owner, Set.of(block(1)));

        assertTrue(lock.canAccess(owner, false));
        assertFalse(lock.canAccess(trusted, false));
        assertTrue(lock.trust(trusted));
        assertTrue(lock.canAccess(trusted, false));
        assertFalse(lock.canManage(trusted, false));
        assertTrue(lock.canAccess(stranger, true));
        assertTrue(lock.canManage(stranger, true));
    }

    @Test
    void ownerCannotBeAddedToOrRemovedFromTrustList() {
        UUID owner = UUID.randomUUID();
        ContainerLock lock = ContainerLock.create(owner, Set.of(block(1)));

        assertFalse(lock.trust(owner));
        assertFalse(lock.untrust(owner));
        assertTrue(lock.trustedPlayers().isEmpty());
    }

    @Test
    void publicModeAndOwnershipTransferUpdateAccessSafely() {
        UUID owner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ContainerLock lock = ContainerLock.create(owner, Set.of(block(1)));

        assertTrue(lock.rename("Workshop"));
        assertTrue(lock.accessMode(LockAccessMode.PUBLIC));
        assertTrue(lock.canAccess(stranger, false));
        assertTrue(lock.transfer(newOwner));

        assertTrue(lock.ownerId().equals(newOwner));
        assertTrue(lock.trustedPlayers().contains(owner));
        assertFalse(lock.trustedPlayers().contains(newOwner));
        assertTrue(lock.name().equals("Workshop"));
    }

    @Test
    void depositOnlyAllowsOpeningButNotWithdrawalAndAutomationIsExplicit() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ContainerLock lock = ContainerLock.create(owner, Set.of(block(1)));

        assertTrue(lock.accessMode(LockAccessMode.DEPOSIT_ONLY));
        assertTrue(lock.canAccess(stranger, false));
        assertFalse(lock.canWithdraw(stranger, false));
        assertTrue(lock.canWithdraw(owner, false));
        assertTrue(lock.automationAllowed(true));
        assertTrue(lock.automationAllowed());
    }

    private BlockKey block(int x) {
        return new BlockKey(UUID.randomUUID(), x, 64, 0);
    }
}
