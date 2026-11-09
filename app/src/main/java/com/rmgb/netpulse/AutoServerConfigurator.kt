package com.rmgb.netpulse

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adds a sing-box URLTest outbound over server nodes in an imported profile.
 * The sing-box core performs periodic HTTP health checks through each candidate.
 * This estimates proxy-path health, not latency to a particular game server.
 */
object AutoServerConfigurator {
    const val AUTO_TAG = "netpulse-auto"
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    fun useFastestAvailable(profileJson: String): String {
        val root = JSONObject(profileJson)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("This profile has no outbounds section")
        val excludedTypes = setOf("direct", "block", "dns", "selector", "urltest")
        val candidateTags = linkedSetOf<String>()

        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim()
            val type = outbound.optString("type").trim().lowercase()
            if (tag.isBlank() || tag == AUTO_TAG || type.isBlank() || type in excludedTypes) continue
            // Only concrete server outbounds are measured. Endpoints such as OpenVPN
            // are kept separate because they are not members of the outbound URLTest group.
            if (outbound.optString("server").isBlank() &&
                !outbound.has("server_port") &&
                type != "wireguard"
            ) continue
            candidateTags += tag
        }

        require(candidateTags.size >= 2) {
            "Automatic server selection needs at least two supported server outbounds in this profile"
        }

        val autoGroup = JSONObject()
            .put("type", "urltest")
            .put("tag", AUTO_TAG)
            .put("outbounds", JSONArray(candidateTags.toList()))
            .put("url", TEST_URL)
            .put("interval", "3m")
            .put("tolerance", 50)
            .put("idle_timeout", "30m")
            .put("interrupt_exist_connections", false)

        val updatedOutbounds = JSONArray()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("tag") != AUTO_TAG) updatedOutbounds.put(outbound)
        }
        updatedOutbounds.put(autoGroup)
        root.put("outbounds", updatedOutbounds)

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("final", AUTO_TAG)
        return root.toString()
    }
}
