package com.rmgb.netpulse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GamingProfileConfiguratorTest {
    @Test fun addsSelectedPackagesToTunAllowlistAndRemovesConflictingExclusions() {
        val source = """{"inbounds":[{"type":"tun","tag":"tun-in","auto_route":true,"exclude_package":["com.example.browser"]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
        val result = JSONObject(GamingProfileConfigurator.withGameOnlyRouting(
            source,
            setOf("com.example.game", "com.example.game2")
        ))
        val tun = result.getJSONArray("inbounds").getJSONObject(0)
        val packages = tun.getJSONArray("include_package")
        assertEquals(2, packages.length())
        assertEquals("com.example.game", packages.getString(0))
        assertEquals("com.example.game2", packages.getString(1))
        assertFalse(tun.has("exclude_package"))
        assertEquals("direct", result.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test fun replacesPreviousAllowlistRatherThanSilentlyIncludingOldApps() {
        val source = """{"inbounds":[{"type":"tun","auto_route":true,"include_package":["com.old.game"]}]}"""
        val result = JSONObject(GamingProfileConfigurator.withGameOnlyRouting(source, setOf("com.new.game")))
        val packages = result.getJSONArray("inbounds").getJSONObject(0).getJSONArray("include_package")
        assertEquals(1, packages.length())
        assertEquals("com.new.game", packages.getString(0))
    }

    @Test fun assignsPerGameOutboundsBeforeExistingRouteRules() {
        val source = """{
            "inbounds":[{"type":"tun","auto_route":true}],
            "outbounds":[
                {"type":"vless","tag":"relay-a","server":"a.example","server_port":443},
                {"type":"trojan","tag":"relay-b","server":"b.example","server_port":443},
                {"type":"direct","tag":"direct"}
            ],
            "route":{"rules":[{"domain_suffix":["example.org"],"action":"route","outbound":"direct"}],"final":"relay-a"}
        }"""
        val result = JSONObject(
            GamingProfileConfigurator.withGameOnlyRouting(
                source,
                setOf("com.example.game", "com.example.other"),
                mapOf("com.example.game" to "relay-b")
            )
        )
        val rules = result.getJSONObject("route").getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("com.example.game", rules.getJSONObject(0).getJSONArray("package_name").getString(0))
        assertEquals("route", rules.getJSONObject(0).getString("action"))
        assertEquals("relay-b", rules.getJSONObject(0).getString("outbound"))
        assertTrue(rules.getJSONObject(1).has("domain_suffix"))
    }

    @Test fun rejectsPerGameRoutesForUnselectedPackagesOrUnknownOutboundTags() {
        val source = """{
            "inbounds":[{"type":"tun","auto_route":true}],
            "outbounds":[{"type":"vless","tag":"relay-a","server":"a.example","server_port":443}]
        }"""
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                source, setOf("com.example.game"), mapOf("com.example.other" to "relay-a")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                source, setOf("com.example.game"), mapOf("com.example.game" to "missing")
            )
        }
    }

    @Test fun rejectsTunWithoutAutomaticAndroidRouting() {
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                """{"inbounds":[{"type":"tun","auto_route":false}]}""",
                setOf("com.example.game")
            )
        }
    }

    @Test fun rejectsProfilesWithoutTunInbound() {
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                """{"inbounds":[{"type":"mixed"}]}""",
                setOf("com.example.game")
            )
        }
    }

    @Test fun rejectsEmptyOrInvalidPackageLists() {
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                """{"inbounds":[{"type":"tun"}]}""",
                emptySet()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GamingProfileConfigurator.withGameOnlyRouting(
                """{"inbounds":[{"type":"tun"}]}""",
                setOf("../invalid")
            )
        }
    }
}
