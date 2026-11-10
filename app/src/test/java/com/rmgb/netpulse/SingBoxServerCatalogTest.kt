package com.rmgb.netpulse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxServerCatalogTest {
    private val profile = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in"}],
          "outbounds":[
            {"type":"vless","tag":"node-a","server":"a.example","server_port":443,"uuid":"secret-a"},
            {"type":"trojan","tag":"node-b","server":"b.example","server_port":8443,"password":"secret-b"},
            {"type":"selector","tag":"best-route","outbounds":["node-a","node-b"],"default":"node-a"},
            {"type":"direct","tag":"direct"},
            {"type":"block","tag":"block"}
          ],
          "route":{"final":"node-a","rules":[{"action":"sniff"}]}
        }
    """.trimIndent()

    @Test
    fun listsServerNodesAndGroupsWithoutExposingCredentials() {
        val entries = SingBoxServerCatalog.list(profile)
        assertEquals(3, entries.size)
        assertEquals("node-a", entries.first().tag)
        assertTrue(entries.first().selected)
        assertEquals("a.example", entries.first().server)
        assertEquals(443, entries.first().port)
        val group = entries.first { it.tag == "best-route" }
        assertTrue(group.group)
        assertEquals(2, group.memberCount)
        assertNull(group.server)
        assertFalse(entries.any { it.tag == "direct" || it.tag == "block" })
        assertFalse(entries.toString().contains("secret"))
    }

    @Test
    fun selectingServerChangesOnlyDefaultRouteAndPreservesRulesAndNodes() {
        val selectedJson = SingBoxServerCatalog.select(profile, "node-b")
        val selectedRoot = JSONObject(selectedJson)
        assertEquals("node-b", selectedRoot.getJSONObject("route").getString("final"))
        assertEquals(1, selectedRoot.getJSONObject("route").getJSONArray("rules").length())
        assertEquals(5, selectedRoot.getJSONArray("outbounds").length())
        assertTrue(SingBoxServerCatalog.list(selectedJson).first { it.tag == "node-b" }.selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonServerOutbounds() {
        SingBoxServerCatalog.select(profile, "direct")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownTags() {
        SingBoxServerCatalog.select(profile, "missing-node")
    }
}
