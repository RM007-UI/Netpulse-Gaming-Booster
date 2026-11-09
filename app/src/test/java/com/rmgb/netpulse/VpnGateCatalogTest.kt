package com.rmgb.netpulse

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnGateCatalogTest {
    private val config = """
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

    private fun csv(config: String = config): String {
        val encoded = Base64.encodeToString(config.toByteArray(), Base64.NO_WRAP)
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
    }

    @Test
    fun sortsByReportedPingThenSpeedAndDeduplicates() {
        val fast = config.replace("198.51.100.10", "198.51.100.11")
        val slow = config.replace("198.51.100.10", "198.51.100.12")
        fun row(host: String, ip: String, ping: Int, speed: Int, profile: String) =
            listOf(host, ip, "1", ping.toString(), speed.toString(), "Japan", "JP", "1", "500", "2", "3", "1", "owner", "node",
                Base64.encodeToString(profile.toByteArray(), Base64.NO_WRAP)).joinToString(",")
        val sample = "*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
            row("vpn123456789", "198.51.100.10", 40, 1000000, config) + "\n" +
            row("vpn123456790", "198.51.100.11", 10, 2000000, fast) + "\n" +
            row("vpn123456791", "198.51.100.12", 10, 3000000, slow) + "\n" +
            row("vpn123456791", "198.51.100.12", 10, 3000000, slow) + "\n*end"
        val servers = VpnGateCatalog.parse(sample)
        assertEquals(3, servers.size)
        assertEquals("vpn123456790", servers[0].hostName)
        assertEquals("vpn123456791", servers[1].hostName)
    }

    @Test
    fun rejectsMalformedCsvAndConfigPointingAtAnotherHost() {
        try {
            VpnGateCatalog.parse("#not-the-api")
            throw AssertionError("Expected malformed API response rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("header"))
        }
        val wrongHost = config.replace("198.51.100.10", "203.0.113.5")
        assertTrue(VpnGateCatalog.parse(csv(wrongHost)).isEmpty())
    }

    @Test
    fun parsesQuotedCommaWithinCsvField() {
        val parsed = VpnGateCatalog.parseCsvLine("a,\"hello, world\",c")
        assertEquals(listOf("a", "hello, world", "c"), parsed)
    }
}
