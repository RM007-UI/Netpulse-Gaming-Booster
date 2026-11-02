package com.rmgb.netpulse

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VpnEngineCoordinatorTest {
    @Before fun setUp() = VpnEngineCoordinator.resetForTests()
    @After fun tearDown() = VpnEngineCoordinator.resetForTests()

    @Test fun differentEnginesCannotOwnVpnSlotAtSameTime() {
        assertTrue(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.WIREGUARD))
        assertFalse(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX))
        assertEquals(VpnEngineCoordinator.Engine.WIREGUARD, VpnEngineCoordinator.currentOwner())
    }

    @Test fun ownerCanReacquireAndReleaseItsOwnSlot() {
        assertTrue(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX))
        assertTrue(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX))
        VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
        assertEquals(VpnEngineCoordinator.Engine.SING_BOX, VpnEngineCoordinator.currentOwner())
        VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.SING_BOX)
        assertNull(VpnEngineCoordinator.currentOwner())
    }

    @Test fun releasedSlotCanBeAcquiredByOtherEngine() {
        assertTrue(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.WIREGUARD))
        VpnEngineCoordinator.release(VpnEngineCoordinator.Engine.WIREGUARD)
        assertTrue(VpnEngineCoordinator.acquire(VpnEngineCoordinator.Engine.SING_BOX))
    }
}
