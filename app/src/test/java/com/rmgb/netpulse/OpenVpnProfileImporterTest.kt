package com.rmgb.netpulse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenVpnProfileImporterTest {
    private val minimal = """
        client
        dev tun
        proto udp
        remote vpn.example.net 1194 udp
        auth-user-pass
        remote-cert-tls server
        redirect-gateway def1
        <ca>
        -----BEGIN CERTIFICATE-----
        ZmFrZS1jZXJ0
        -----END CERTIFICATE-----
        </ca>
    """.trimIndent()

    @Test
    fun importsUdpTlsProfileAndPromptsForCredentials() {
        assertThrows(OpenVpnCredentialsRequiredException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal)
        }
        val imported = OpenVpnProfileImporter.importProfile(
            minimal,
            OpenVpnCredentials("alice", "example-secret")
        )
        assertEquals("OpenVPN UDP", imported.protocol)
        val json = JSONObject(imported.json)
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("openvpn-client", endpoint.getString("type"))
        assertEquals("udp", endpoint.getString("network"))
        assertEquals("alice", endpoint.getString("username"))
        assertEquals("example-secret", endpoint.getString("password"))
        assertTrue(endpoint.getBoolean("redirect_gateway"))
        assertEquals("1.2", endpoint.getJSONObject("tls").getString("version_min"))
        assertFalse(json.has("outbound"))
    }

    @Test
    fun importsInlineAuthenticationAndTlsCrypt() {
        val profile = minimal.replace("auth-user-pass", "auth-user-pass\n<auth-user-pass>\nalice\nexample-secret\n</auth-user-pass>")
            .replace("</ca>", "</ca>\n<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\nkey\n-----END OpenVPN Static key V1-----\n</tls-crypt>")
        val imported = OpenVpnProfileImporter.importProfile(profile)
        val endpoint = JSONObject(imported.json).getJSONArray("endpoints").getJSONObject(0)
        assertEquals("tls_crypt", endpoint.getJSONObject("tls").getJSONObject("control_wrap").getString("type"))
    }

    @Test
    fun mapsMultipleUdpAndTcpRemotes() {
        val profile = minimal
            .replace("proto udp", "proto udp")
            .replace("remote vpn.example.net 1194 udp", "remote udp.example.net 1194 udp\nremote tcp.example.net 443 tcp-client")
            .replace("auth-user-pass", "")
        val imported = OpenVpnProfileImporter.importProfile(profile)
        val endpoint = JSONObject(imported.json).getJSONArray("endpoints").getJSONObject(0)
        assertEquals(2, endpoint.getJSONArray("servers").length())
        assertEquals("OpenVPN UDP", imported.protocol)
    }

    @Test
    fun rejectsUnsafeOrUnsupportedDirectivesAndMissingTrustAnchor() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal.replace("</ca>", "</ca>\ncompress lz4"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal.replace("-----BEGIN CERTIFICATE-----\nZmFrZS1jZXJ0\n-----END CERTIFICATE-----", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal.replace("redirect-gateway def1", "up /tmp/start.sh"))
        }
    }

    @Test
    fun rejectsInlinePkcs12BundlesInsteadOfSilentlyDroppingThem() {
        val profile = minimal
            .replace("auth-user-pass", "")
            .replace("</ca>", "</ca>\\n<pkcs12>\\nnot-a-p12\\n</pkcs12>")
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(profile)
        }
    }

    @Test
    fun supportsExplicitExitNotifyFlagWithoutAnArgumentForUdp() {
        val profile = minimal.replace(
            "auth-user-pass",
            "auth-user-pass\\nexplicit-exit-notify"
        )
        val imported = OpenVpnProfileImporter.importProfile(
            profile,
            OpenVpnCredentials("alice", "example-secret")
        )
        val endpoint = JSONObject(imported.json).getJSONArray("endpoints").getJSONObject(0)
        assertEquals(1, endpoint.getInt("explicit_exit_notify"))
    }

    @Test
    fun labelsMixedRemoteTransportProfilesAccurately() {
        val profile = minimal.replace(
            "remote vpn.example.net 1194 udp",
            "remote udp.example.net 1194 udp\\nremote tcp.example.net 443 tcp-client"
        )
        val imported = OpenVpnProfileImporter.importProfile(
            profile.replace("auth-user-pass", ""),
            null
        )
        assertEquals("OpenVPN UDP/TCP", imported.protocol)
    }

    @Test
    fun rejectsExternalClientKeyFilesAndOldTlsVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal.replace("<ca>", "ca /tmp/ca.crt\n<ca>"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenVpnProfileImporter.importProfile(minimal.replace("remote-cert-tls server", "remote-cert-tls server\ntls-version-min 1.0"))
        }
    }
}
