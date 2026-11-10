package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnGateCatalogTest {
    private val sampleProfile = """
        client
        dev tun
        proto udp
        remote 198.51.100.10 1194 udp
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        remote-cert-tls server
        cipher AES-128-CBC
        auth SHA1
        auth-user-pass
        verb 3
        <ca>
        -----BEGIN CERTIFICATE-----
        ZmFrZS1jZXJ0
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()

    private fun csv(profile: String = sampleProfile): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(profile.toByteArray())
        return """
            *vpn_servers
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            vpn123456789,198.51.100.10,100,22,120000000,Japan,JP,4,1000,25,5000,1,owner,"test, server",$encoded
            *end
        """.trimIndent()
    }

    @Test
    fun parsesOfficialCsvAndDecodesOvpnProfile() {
        val servers = VpnGateCatalog.parse(csv())
        assertEquals(1, servers.size)
        assertEquals("vpn123456789", servers[0].hostName)
        assertEquals("Japan", servers[0].country)
        assertEquals(22.0, servers[0].pingMs!!, 0.0)
        assertEquals(120.0, servers[0].speedMbps!!, 0.0)
        assertTrue(servers[0].config.contains("<ca>"))
        assertEquals(1, VpnGateCatalog.lastDiagnostics.rowsSeen)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.rowsWithMetadata)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.profilesChecked)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.profilesDecoded)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.profilesAccepted)
        val imported = OpenVpnProfileImporter.importProfile(
            servers[0].config,
            OpenVpnCredentials("vpn", "vpn")
        )
        assertEquals("OpenVPN UDP", imported.protocol)
    }

    @Test
    fun sortsByReportedPingThenSpeedAndDeduplicates() {
        val fast = sampleProfile.replace("198.51.100.10", "198.51.100.11")
        val slow = sampleProfile.replace("198.51.100.10", "198.51.100.12")
        fun row(host: String, ip: String, ping: Int, speed: Int, profile: String) =
            listOf(host, ip, "1", ping.toString(), speed.toString(), "Japan", "JP", "1", "500", "2", "3", "1", "owner", "node",
                java.util.Base64.getEncoder().encodeToString(profile.toByteArray())).joinToString(",")
        val sample = "*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
            row("vpn123456789", "198.51.100.10", 40, 1000000, sampleProfile) + "\n" +
            row("vpn123456790", "198.51.100.11", 10, 2000000, fast) + "\n" +
            row("vpn123456791", "198.51.100.12", 10, 3000000, slow) + "\n" +
            row("vpn123456791", "198.51.100.12", 10, 3000000, slow) + "\n*end"
        val servers = VpnGateCatalog.parse(sample)
        assertEquals(3, servers.size)
        assertEquals("vpn123456791", servers[0].hostName)
        assertEquals("vpn123456790", servers[1].hostName)
    }

    @Test
    fun rejectsMalformedCsvAndConfigPointingAtAnotherHost() {
        try {
            VpnGateCatalog.parse("#not-the-api")
            throw AssertionError("Expected malformed API response rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("header"))
        }
        val wrongHost = sampleProfile.replace("198.51.100.10", "203.0.113.5")
        assertTrue(VpnGateCatalog.parse(csv(wrongHost)).isEmpty())
    }

    @Test
    fun activatesCommentedVpnGateAuthDirectiveForInAppCredentials() {
        val publicProfile = sampleProfile.replace("auth-user-pass", "#auth-user-pass")
        val normalized = VpnGateCatalog.ensureInAppUserPass(publicProfile)
        val parsed = VpnGateCatalog.parse(csv(publicProfile))
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().config.contains("\nauth-user-pass\n"))
        assertTrue(Regex("(?m)^\\s*auth-user-pass\\s*$").containsMatchIn(normalized))
        val imported = OpenVpnProfileImporter.importProfile(
            normalized,
            OpenVpnCredentials("vpn", "vpn")
        )
        assertEquals("OpenVPN UDP", imported.protocol)
        assertTrue(normalized.contains("<ca>"))
    }

    @Test
    fun removesExternalCredentialFilePathFromPublicProfile() {
        val publicProfile = sampleProfile.replace("auth-user-pass", "auth-user-pass /sdcard/credentials.txt")
        val normalized = VpnGateCatalog.ensureInAppUserPass(publicProfile)
        assertTrue(Regex("(?m)^auth-user-pass$").containsMatchIn(normalized))
        assertFalse(normalized.contains("/sdcard/credentials.txt"))
        OpenVpnProfileImporter.importProfile(normalized, OpenVpnCredentials("vpn", "vpn"))
    }

    @Test
    fun preservesMultilineCertificateBlocksWhenNormalizingProfile() {
        val normalized = VpnGateCatalog.ensureInAppUserPass(sampleProfile.replace("\n", "\r\n"))
        assertTrue(normalized.contains("\n<ca>\n"))
        assertTrue(normalized.contains("-----BEGIN CERTIFICATE-----"))
        OpenVpnProfileImporter.importProfile(normalized, OpenVpnCredentials("vpn", "vpn"))
    }

    @Test
    fun usesTopLevelProtoWhenRemoteOmitsItsTransportToken() {
        val configWithoutRemoteProto = sampleProfile.replace(
            "remote 198.51.100.10 1194 udp",
            "remote 198.51.100.10 1194"
        )
        val parsed = VpnGateCatalog.parse(csv(configWithoutRemoteProto))
        assertEquals(1, parsed.size)
        assertEquals(
            "OpenVPN UDP",
            OpenVpnProfileImporter.importProfile(
                parsed.single().config,
                OpenVpnCredentials("vpn", "vpn")
            ).protocol
        )
    }

    @Test
    fun exposesReasonsWhenProfilesFailImporterValidation() {
        val unsupported = sampleProfile.replace(
            "auth-user-pass",
            "auth-user-pass\\nunsupported-vpn-gate-directive value"
        )
        val parsed = VpnGateCatalog.parse(csv(unsupported))
        assertTrue(parsed.isEmpty())
        assertEquals(1, VpnGateCatalog.lastDiagnostics.rowsSeen)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.profilesChecked)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.profilesDecoded)
        assertEquals(1, VpnGateCatalog.lastDiagnostics.rejected["importer"])
        assertTrue(VpnGateCatalog.lastDiagnostics.examples.any { it.contains("unsupported-vpn-gate-directive") })
    }

    @Test
    fun limitsExpensiveProfileValidationAndReturnsBestCandidates() {
        val rows = (0..450).joinToString("\\n") { index ->
            val host = "vpn${index.toString().padStart(9, '0')}.opengw.net"
            val ip = "198.51.100.${(index % 240) + 1}"
            val profile = sampleProfile.replace("198.51.100.10", ip)
            listOf(
                host, ip, "100", (index + 1).toString(), "1000000",
                "Japan", "JP", "2", "200", "10", "100", "1", "owner", "test",
                java.util.Base64.getEncoder().encodeToString(profile.toByteArray())
            ).joinToString(",")
        }
        val header = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"
        val parsed = VpnGateCatalog.parse("*vpn_servers\\n" + header + "\\n" + rows + "\\n*end")
        assertTrue(parsed.isNotEmpty())
        assertTrue(VpnGateCatalog.lastDiagnostics.profilesChecked <= 400)
        assertTrue(parsed.size <= 60)
    }

    @Test
    fun parsesQuotedCommaWithinCsvField() {
        val parsed = VpnGateCatalog.parseCsvLine("a,\"hello, world\",c")
        assertEquals(listOf("a", "hello, world", "c"), parsed)
    }
}
