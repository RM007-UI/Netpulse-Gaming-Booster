package com.rmgb.netpulse

import org.json.JSONObject

/**
 * Read-only inventory and safe default-route selection for user-imported sing-box outbounds.
 *
 * It intentionally exposes endpoint metadata only (never UUIDs, passwords, keys or TLS data).
 * Selecting an entry changes route.final in a copied profile; route rules and the original
 * profile's other settings are preserved. The caller must run Libbox.checkConfig afterwards.
 */
data class SingBoxServerEntry(
    val tag: String,
    val type: String,
    val server: String?,
    val port: Int?,
    val selected: Boolean,
    val group: Boolean,
    val memberCount: Int
)

object SingBoxServerCatalog {
    private val nonSelectableTypes = setOf("direct", "block", "dns")
    private val groupTypes = setOf("selector", "urltest")

    fun list(profileJson: String): List<SingBoxServerEntry> {
        val root = JSONObject(profileJson)
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val selectedTag = root.optJSONObject("route")?.optString("final").orEmpty()
        val entries = ArrayList<SingBoxServerEntry>(outbounds.length())

        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim()
            val type = outbound.optString("type").trim().lowercase()
            if (tag.isBlank() || type.isBlank() || type in nonSelectableTypes) continue

            val group = type in groupTypes
            val server = outbound.optString("server").trim().takeIf { it.isNotEmpty() }
            if (!group && server == null) continue

            val port = if (outbound.has("server_port")) {
                outbound.optInt("server_port", -1).takeIf { it in 1..65535 }
            } else {
                null
            }
            val members = outbound.optJSONArray("outbounds")?.length() ?: 0

            entries += SingBoxServerEntry(
                tag = tag,
                type = type,
                server = server,
                port = port,
                selected = tag == selectedTag,
                group = group,
                memberCount = members
            )
        }

        return entries.sortedWith(
            compareByDescending<SingBoxServerEntry> { it.selected }
                .thenByDescending { it.group }
                .thenBy { it.tag.lowercase() }
        )
    }

    /**
     * Change only route.final. Profiles with explicit route rules retain those rules;
     * the selected outbound becomes the default for traffic not matched by a rule.
     */
    fun select(profileJson: String, tag: String): String {
        require(tag.isNotBlank()) { "Server tag is empty" }
        val selected = list(profileJson).firstOrNull { it.tag == tag }
            ?: throw IllegalArgumentException("The selected server does not exist in this profile")

        val root = JSONObject(profileJson)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("final", selected.tag)
        return root.toString()
    }
}
