package com.rmgb.netpulse.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCatalogTest {
    @Test fun wireGuardAndSingBoxOutboundsAreAdvertisedAsAvailable() {
        val available = ProtocolCatalog.options.filter { it.engineState == EngineState.AVAILABLE }.map { it.id }
        assertTrue(available.contains("wireguard"))
        assertTrue(available.containsAll(listOf(
            "socks5", "shadowsocks", "vless", "vmess", "trojan",
            "hysteria", "hysteria2", "tuic", "anytls", "ssh",
            "http_proxy", "naive"
        )))
    }

    @Test fun transportsAreNotVpnTunnelEngines() {
        val transports = ProtocolCatalog.options.filter { it.family == ProtocolFamily.TRANSPORT }
        assertTrue(transports.isNotEmpty())
        assertTrue(transports.all { it.engineState == EngineState.NOT_A_TUNNEL_ENGINE })
    }

    @Test fun socks5IsProvidedByTheSharedSingBoxTunEngine() {
        val socks5 = ProtocolCatalog.options.single { it.id == "socks5" }
        assertEquals(ProtocolFamily.PROXY, socks5.family)
        assertEquals(EngineState.AVAILABLE, socks5.engineState)
        assertTrue(socks5.description.contains("sing-box"))
    }

    @Test fun newlySupportedShareLinkProtocolsAreAdvertised() {
        assertEquals(EngineState.AVAILABLE, ProtocolCatalog.options.single { it.id == "hysteria" }.engineState)
        assertEquals(EngineState.AVAILABLE, ProtocolCatalog.options.single { it.id == "anytls" }.engineState)
        assertEquals(EngineState.AVAILABLE, ProtocolCatalog.options.single { it.id == "naive" }.engineState)
    }

    @Test fun openVpnAndIkev2EnginesAreAdvertisedWithTheirRealPlatformRequirements() {
        val openVpnUdp = ProtocolCatalog.options.single { it.id == "openvpn_udp" }
        val openVpnTcp = ProtocolCatalog.options.single { it.id == "openvpn_tcp" }
        val ikev2 = ProtocolCatalog.options.single { it.id == "ikev2_ipsec" }
        assertEquals(EngineState.AVAILABLE, openVpnUdp.engineState)
        assertEquals(EngineState.AVAILABLE, openVpnTcp.engineState)
        assertTrue(openVpnUdp.description.contains("sing-box"))
        assertEquals(EngineState.AVAILABLE, ikev2.engineState)
        assertTrue(ikev2.description.contains("Android 11"))
    }
}
