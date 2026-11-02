package com.rmgb.netpulse.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCatalogTest {
    @Test fun onlyWireGuardIsAdvertisedAsAvailable() {
        val available = ProtocolCatalog.options.filter { it.engineState == EngineState.AVAILABLE }
        assertEquals(listOf("wireguard"), available.map { it.id })
    }

    @Test fun transportsAreNotVpnTunnelEngines() {
        val transports = ProtocolCatalog.options.filter { it.family == ProtocolFamily.TRANSPORT }
        assertTrue(transports.isNotEmpty())
        assertTrue(transports.all { it.engineState == EngineState.NOT_A_TUNNEL_ENGINE })
    }

    @Test fun socks5IsClearlyClassifiedAsProxy() {
        val socks5 = ProtocolCatalog.options.single { it.id == "socks5" }
        assertEquals(ProtocolFamily.PROXY, socks5.family)
        assertTrue(socks5.description.contains("TUN"))
    }
}
