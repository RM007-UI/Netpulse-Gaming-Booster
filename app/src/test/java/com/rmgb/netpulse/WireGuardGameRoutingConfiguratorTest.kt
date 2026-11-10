package com.rmgb.netpulse

import com.wireguard.config.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class WireGuardGameRoutingConfiguratorTest {
    private val sampleConfig = """
        [Interface]
        PrivateKey = 2GZ2pY7hX4oB9vGx0xk4pJqF6uY7t3nR8mV1wC5sD0E=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        MTU = 1380
        [Peer]
        PublicKey = 3GZ2pY7hX4oB9vGx0xk4pJqF6uY7t3nR8mV1wC5sD0E=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    private fun parse() = Config.parse(ByteArrayInputStream(sampleConfig.toByteArray()))

    @Test
    fun appliesOnlySelectedPackagesAndKeepsImportedConfigImmutable() {
        val original = parse()
        val result = WireGuardGameRoutingConfigurator.withGameOnlyRouting(
            original,
            setOf("com.example.game", "com.example.game2")
        )
        assertEquals(2, result.getInterface().getIncludedApplications().size)
        assertTrue("com.example.game" in result.getInterface().getIncludedApplications())
        assertTrue("com.example.game2" in result.getInterface().getIncludedApplications())
        assertTrue(original.getInterface().getIncludedApplications().isEmpty())
        assertTrue(result.getInterface().getExcludedApplications().isEmpty())
        assertEquals(original.getPeers(), result.getPeers())
        assertEquals(original.getInterface().getAddresses(), result.getInterface().getAddresses())
        assertEquals(original.getInterface().getDnsServers(), result.getInterface().getDnsServers())
        assertEquals(1380, result.getInterface().getMtu().get())
    }

    @Test
    fun replacesExistingWireGuardAppRules() {
        val config = Config.parse(
            ByteArrayInputStream(
                sampleConfig.replace(
                    "MTU = 1380",
                    "MTU = 1380\nIncludedApplications = com.old.game"
                ).toByteArray()
            )
        )
        val result = WireGuardGameRoutingConfigurator.withGameOnlyRouting(
            config,
            setOf("com.new.game")
        )
        assertEquals(setOf("com.new.game"), result.getInterface().getIncludedApplications())
    }

    @Test
    fun rejectsInvalidOrEmptyPackageLists() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardGameRoutingConfigurator.withGameOnlyRouting(parse(), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardGameRoutingConfigurator.withGameOnlyRouting(parse(), setOf("../invalid"))
        }
    }
}
