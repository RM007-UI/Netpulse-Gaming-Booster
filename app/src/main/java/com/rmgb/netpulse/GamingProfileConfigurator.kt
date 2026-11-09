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

    fun withGameOnlyRouting(profileJson: String, selectedPackages: Set<String>): String {
        require(selectedPackages.isNotEmpty()) { "Select at least one game before enabling game-only routing" }
        require(selectedPackages.size <= 50) { "At most 50 apps can be selected" }
        require(selectedPackages.all { packagePattern.matches(it) }) { "The selected app list contains an invalid package name" }

        val root = JSONObject(profileJson)
        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("This profile has no sing-box inbounds section")

        var tunCount = 0
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (!inbound.optString("type").equals("tun", ignoreCase = true)) continue
            tunCount++

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
