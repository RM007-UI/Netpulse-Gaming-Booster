package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SingBoxProfileImporterTest {
    @Test fun detectsSupportedShareLinkSchemes() {
        assertEquals("VLESS", SingBoxProfileImporter.detectProtocol("vless://user@example.com:443"))
        assertEquals("VMess", SingBoxProfileImporter.detectProtocol("vmess://YWJj"))
        assertEquals("Trojan", SingBoxProfileImporter.detectProtocol("trojan://secret@example.com:443"))
        assertEquals("Shadowsocks", SingBoxProfileImporter.detectProtocol("ss://YWJj"))
        assertEquals("SOCKS5", SingBoxProfileImporter.detectProtocol("socks5://example.com:1080"))
        assertEquals("Hysteria", SingBoxProfileImporter.detectProtocol("hysteria://secret@example.com:443"))
        assertEquals("Hysteria2", SingBoxProfileImporter.detectProtocol("hysteria2://secret@example.com:443"))
        assertEquals("AnyTLS", SingBoxProfileImporter.detectProtocol("anytls://secret@example.com:443"))
        assertEquals("NaiveProxy", SingBoxProfileImporter.detectProtocol("naive+https://user:secret@example.com:443"))
        assertEquals("TUIC", SingBoxProfileImporter.detectProtocol("tuic://uuid:secret@example.com:443"))
        assertEquals("HTTP proxy", SingBoxProfileImporter.detectProtocol("https://example.com:443"))
        assertEquals("sing-box JSON", SingBoxProfileImporter.detectProtocol("{\"outbounds\":[]}"))
    }

    @Test fun importsHysteriaV1WhenBothBandwidthHintsAreExplicit() {
        val profile = SingBoxProfileImporter.importProfile(
            "hysteria://secret@example.com:443?upmbps=30&downmbps=120&sni=edge.example.com&obfs=game-key#Game"
        )
        assertEquals("Hysteria", profile.protocol)
        assertTrue(profile.json.contains("\"type\":\"hysteria\""))
        assertTrue(profile.json.contains("\"auth_str\":\"secret\""))
        assertTrue(profile.json.contains("\"up_mbps\":30"))
        assertTrue(profile.json.contains("\"down_mbps\":120"))
        assertTrue(profile.json.contains("\"server_name\":\"edge.example.com\""))
    }

    @Test fun rejectsHysteriaV1WithoutExplicitBandwidthHints() {
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.importProfile("hysteria://secret@example.com:443")
        }
    }

    @Test fun importsAnyTlsAndNaiveProxyLinks() {
        val anyTls = SingBoxProfileImporter.importProfile(
            "anytls://secret@example.com:443?sni=edge.example.com#AnyTLS"
        )
        assertEquals("AnyTLS", anyTls.protocol)
        assertTrue(anyTls.json.contains("\"type\":\"anytls\""))
        assertTrue(anyTls.json.contains("\"password\":\"secret\""))

        val naive = SingBoxProfileImporter.importProfile(
            "naive+https://player:secret@example.com:443?sni=edge.example.com#Naive"
        )
        assertEquals("NaiveProxy", naive.protocol)
        assertTrue(naive.json.contains("\"type\":\"naive\""))
        assertTrue(naive.json.contains("\"username\":\"player\""))
        assertTrue(naive.json.contains("\"password\":\"secret\""))
    }

    @Test fun refusesLinksThatDisableTlsCertificateValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.importProfile(
                "anytls://secret@example.com:443?insecure=true"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.importProfile(
                "naive+https://player:secret@example.com:443?skip_cert_verify=1"
            )
        }
    }

    @Test fun rejectsBlankAndUnknownSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.detectProtocol("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.detectProtocol("unknown://example.com")
        }
    }

    @Test fun refusesMultipleShareLinksInOneTextImport() {
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxProfileImporter.importProfile(
                "vless://user@example.com:443\ntrojan://secret@example.com:443"
            )
        }
    }
}
