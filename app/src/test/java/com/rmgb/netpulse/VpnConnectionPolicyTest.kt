package com.rmgb.netpulse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionPolicyTest {
    @Test
    fun allowsStartWhenNoVpnNetworkIsActive() {
        assertFalse(VpnConnectionPolicy.isForeignVpnActive(false, false))
    }

    @Test
    fun blocksWhenAnotherVpnOwnsTheAndroidVpnSlot() {
        assertTrue(VpnConnectionPolicy.isForeignVpnActive(true, false))
    }

    @Test
    fun doesNotTreatAnExistingNetPulseTunnelAsForeign() {
        assertFalse(VpnConnectionPolicy.isForeignVpnActive(true, true))
    }
}
