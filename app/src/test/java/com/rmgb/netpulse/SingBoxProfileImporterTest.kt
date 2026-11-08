package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SingBoxProfileImporterTest {
    @Test fun detectsSupportedShareLinkSchemes() {
        assertEquals("VLESS", SingBoxProfileImporter.detectProtocol("vless://user@example.com:443"))
        assertEquals("VMess", SingBoxProfileImporter.detectProtocol("vmess://YWJj"))
        assertEquals("Trojan", SingBoxProfileImporter.detectProtocol("trojan://secret@example.com:443"))
        assertEquals("Shadowsocks", SingBoxProfileImporter.detectProtocol("ss://YWJj"))
        assertEquals("SOCKS5", SingBoxProfileImporter.detectProtocol("socks5://example.com:1080"))
        assertEquals("Hysteria2", SingBoxProfileImporter.detectProtocol("hysteria2://secret@example.com:443"))
        assertEquals("TUIC", SingBoxProfileImporter.detectProtocol("tuic://uuid:secret@example.com:443"))
        assertEquals("HTTP proxy", SingBoxProfileImporter.detectProtocol("https://example.com:443"))
        assertEquals("sing-box JSON", SingBoxProfileImporter.detectProtocol("{\"outbounds\":[]}"))
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
