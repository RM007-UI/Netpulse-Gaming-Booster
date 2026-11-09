package com.rmgb.netpulse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AutoServerConfiguratorTest {
    private val source = """
        {
          "outbounds":[
            {"type":"vless","tag":"node-a","server":"a.example","server_port":443},
            {"type":"trojan","tag":"node-b","server":"b.example","server_port":443},
            {"type":"direct","tag":"direct"}
          ],
          "route":{"final":"node-a","rules":[{"domain_suffix":["example.org"],"action":"route","outbound":"direct"}]}
        }
    """.trimIndent()

    @Test
    fun createsUrlTestGroupAndChangesOnlyDefaultRoute() {
        val result = JSONObject(AutoServerConfigurator.useFastestAvailable(source))
        val outbounds = result.getJSONArray("outbounds")
        val auto = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .single { it.optString("tag") == AutoServerConfigurator.AUTO_TAG }
        assertEquals("urltest", auto.getString("type"))
        assertEquals(2, auto.getJSONArray("outbounds").length())
        assertEquals(AutoServerConfigurator.TEST_URL, auto.getString("url"))
        assertEquals(50, auto.getInt("tolerance"))
        assertEquals(AutoServerConfigurator.AUTO_TAG, result.getJSONObject("route").getString("final"))
        assertEquals(1, result.getJSONObject("route").getJSONArray("rules").length())
    }

    @Test
    fun replacesExistingAutoGroupWithoutDuplicatingIt() {
        val first = AutoServerConfigurator.useFastestAvailable(source)
        val second = AutoServerConfigurator.useFastestAvailable(first)
        val outbounds = JSONObject(second).getJSONArray("outbounds")
        assertEquals(1, (0 until outbounds.length()).count {
            outbounds.getJSONObject(it).optString("tag") == AutoServerConfigurator.AUTO_TAG
        })
    }

    @Test
    fun rejectsProfilesWithLessThanTwoServerNodes() {
        val oneNode = """{"outbounds":[{"type":"vless","tag":"one","server":"one.example","server_port":443}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AutoServerConfigurator.useFastestAvailable(oneNode)
        }
    }
}
