package com.rmgb.netpulse

import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies Android per-app VPN routing to a copied sing-box profile.
 *
 * Only the selected packages are included in the VPN. Other applications keep
 * using Android's normal network. The imported source profile remains untouched.
 */
object GamingProfileConfigurator {
    private val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun withGameOnlyRouting(
        profileJson: String,
        selectedPackages: Set<String>,
        perGameOutbounds: Map<String, String> = emptyMap()
    ): String {
        require(selectedPackages.isNotEmpty()) { "Select at least one game before enabling game-only routing" }
        require(selectedPackages.size <= 51) { "Select at most 50 games plus the NetPulse diagnostics app" }
        require(selectedPackages.all { packagePattern.matches(it) }) { "The selected app list contains an invalid package name" }

        require(perGameOutbounds.keys.all { it in selectedPackages }) {
            "A per-game route can only be assigned to a selected app"
        }
        require(perGameOutbounds.values.all(String::isNotBlank)) {
            "A per-game outbound tag cannot be empty"
        }

        val root = JSONObject(profileJson)
        if (perGameOutbounds.isNotEmpty()) {
            val outbounds = root.optJSONArray("outbounds")
                ?: throw IllegalArgumentException("This profile has no outbounds section")
            val availableTags = (0 until outbounds.length()).mapNotNull { index ->
                outbounds.optJSONObject(index)?.optString("tag")?.takeIf(String::isNotBlank)
            }.toSet()
            require(perGameOutbounds.values.all { it in availableTags }) {
                "A selected per-game route does not exist in the active sing-box profile"
            }
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val existingRules = route.optJSONArray("rules") ?: JSONArray()
            val rules = JSONArray()
            perGameOutbounds.toSortedMap().forEach { (appPackage, outboundTag) ->
                rules.put(
                    JSONObject()
                        .put("package_name", JSONArray().put(appPackage))
                        .put("action", "route")
                        .put("outbound", outboundTag)
                )
            }
            for (index in 0 until existingRules.length()) {
                rules.put(existingRules.get(index))
            }
            route.put("rules", rules)
        }

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("This profile has no sing-box inbounds section")

        var tunCount = 0
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (!inbound.optString("type").equals("tun", ignoreCase = true)) continue
            tunCount++
            require(inbound.optBoolean("auto_route", false)) {
                "The TUN inbound must enable auto_route for Android per-app routing"
            }

            val include = JSONArray()
            selectedPackages.sorted().forEach(include::put)
            inbound.put("include_package", include)

            // Android's VpnService.Builder cannot combine allowed and disallowed app sets.
            // Game-only mode is an allowlist, so stale exclusions must be removed.
            inbound.remove("exclude_package")
        }

        require(tunCount > 0) { "This profile has no TUN inbound; game-only routing needs a TUN profile" }
        return root.toString()
    }
}
