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
