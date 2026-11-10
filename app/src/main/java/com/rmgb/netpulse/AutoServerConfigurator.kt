package com.rmgb.netpulse

import org.json.JSONArray
import org.json.JSONObject

/**
 * Creates a sing-box URLTest outbound using only endpoint outbounds already
 * present in the imported profile. The core performs its configured periodic
 * health checks after the tunnel starts; this is not a precomputed game ping.
 */
object AutoServerConfigurator {
    const val AUTO_TAG = "netpulse-auto-urltest"
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    fun useFastestAvailable(profileJson: String): String {
        val root = JSONObject(profileJson)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Profile has no outbounds")
        val candidates = SingBoxServerCatalog.list(profileJson)
            .filter { !it.group && it.tag != AUTO_TAG }
        require(candidates.size >= 2) {
            "At least two selectable server endpoints are required for URLTest"
        }

        val existingIndex = (0 until outbounds.length()).firstOrNull { index ->
            outbounds.optJSONObject(index)?.optString("tag") == AUTO_TAG
        }
        val group = JSONObject()
            .put("type", "urltest")
            .put("tag", AUTO_TAG)
            .put("outbounds", JSONArray().apply { candidates.forEach { put(it.tag) } })
            .put("url", TEST_URL)
            .put("interval", "3m")
            .put("tolerance", 50)
            .put("idle_timeout", "30m")
            .put("interrupt_exist_connections", false)

        if (existingIndex == null) outbounds.put(group) else outbounds.put(existingIndex, group)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("final", AUTO_TAG)
        return root.toString()
    }
}
