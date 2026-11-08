package com.rmgb.netpulse

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Turns a single proxy share link or a sing-box JSON profile into an Android TUN-ready
 * sing-box configuration. Credentials are kept in memory and are never logged here.
 */
internal object SingBoxProfileImporter {
    data class Result(
        val json: String,
        val protocolId: String,
        val displayName: String
    )

    fun importProfile(input: String): Result {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Profile is empty" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 2 * 1024 * 1024) {
            "Profile exceeds the 2 MiB limit"
        }
        if (raw.startsWith("{")) return importJson(raw)
        val candidate = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(candidate.size == 1) {
            "Import one share link at a time, or use a sing-box JSON configuration"
        }
        return importShareLink(candidate.single())
    }

    private fun importJson(raw: String): Result {
        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("The JSON profile has no outbounds")
        require(outbounds.length() > 0) { "The JSON profile has no outbounds" }

        var selectedType = "sing-box"
        var selectedTag = ""
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val type = outbound.optString("type")
            if (type.isNotBlank() && type !in setOf("direct", "block", "dns")) {
                selectedType = type
                selectedTag = outbound.optString("tag")
                break
            }
        }

        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also {
            root.put("inbounds", it)
        }
        val hasTun = (0 until inbounds.length()).any { index ->
            inbounds.optJSONObject(index)?.optString("type") == "tun"
        }
        if (!hasTun) inbounds.put(defaultTunInbound())

        val route = root.optJSONObject("route") ?: JSONObject().also {
            root.put("route", it)
        }
        if (!route.has("auto_detect_interface")) route.put("auto_detect_interface", true)
        if (route.optString("final").isBlank()) {
            val firstProxy = (0 until outbounds.length()).asSequence()
                .mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { it.optString("type") !in setOf("direct", "block", "dns") }
            route.put("final", firstProxy?.optString("tag")?.takeIf(String::isNotBlank) ?: "direct")
        }

        // A TUN profile without DNS configuration can leave Android apps unable to resolve
        // names. Use a pinned-IP DoH endpoint over the selected proxy route, not a local leak.
        if (!root.has("dns")) {
            val detour = route.optString("final").ifBlank { "direct" }
            val dnsServer = JSONObject()
                .put("type", "https")
                .put("tag", "netpulse-doh")
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("path", "/dns-query")
                .put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
                .put("detour", detour)
            root.put("dns", JSONObject()
                .put("servers", JSONArray().put(dnsServer))
                .put("final", "netpulse-doh")
                .put("strategy", "prefer_ipv4"))
        }
        return Result(root.toString(), selectedType, selectedTag.ifBlank { selectedType })
    }

    private fun importShareLink(link: String): Result {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> importVless(link)
            "vmess" -> importVmess(link)
            "trojan" -> importTrojan(link)
            "ss", "shadowsocks" -> importShadowsocks(link)
            "socks", "socks5", "socks5h" -> importSocks(link)
            else -> throw IllegalArgumentException("Unsupported share-link type")
        }
    }

    private fun importVless(link: String): Result {
        val uri = URI(link)
        val userId = decoded(uri.rawUserInfo).substringBefore(':')
        require(userId.isNotBlank()) { "VLESS user ID is missing" }
        val query = query(uri.rawQuery)
        val outbound = baseOutbound("vless", uri, "uuid", userId)
        outbound.put("network", query["network"] ?: "tcp")
        query["flow"]?.takeIf(String::isNotBlank)?.let {
            require(it == "xtls-rprx-vision") { "Unsupported VLESS flow" }
            outbound.put("flow", it)
        }
        addTls(outbound, uri.host, query, allowReality = true)
        addTransport(outbound, query)
        return result(outbound, "vless", label(uri))
    }

    private fun importVmess(link: String): Result {
        val encoded = link.removePrefix("vmess://").substringBefore('#').trim()
        val decoded = decodeBase64(encoded)
        val source = JSONObject(decoded)
        val host = source.optString("add")
        val port = source.optString("port").toIntOrNull()
            ?: throw IllegalArgumentException("VMess server port is invalid")
        val uuid = source.optString("id")
        require(host.isNotBlank() && uuid.isNotBlank() && port in 1..65535) {
            "VMess server details are incomplete"
        }
        val outbound = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("security", source.optString("scy").ifBlank { "auto" })
            .put("alter_id", source.optInt("aid", 0))
        val network = source.optString("net").ifBlank { "tcp" }
        outbound.put("network", if (network == "h2") "tcp" else network)
        val query = mutableMapOf<String, String>()
        source.optString("sni").takeIf(String::isNotBlank)?.let { query["sni"] = it }
        source.optString("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
        source.optString("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
        source.optString("fp").takeIf(String::isNotBlank)?.let { query["fp"] = it }
        source.optString("alpn").takeIf(String::isNotBlank)?.let { query["alpn"] = it }
        if (source.optString("tls").equals("tls", true)) addTls(outbound, host, query, allowReality = false)
        addTransport(outbound, query, explicitType = if (network == "h2") "http" else network)
        return result(outbound, "vmess", source.optString("ps").ifBlank { "VMess" })
    }

    private fun importTrojan(link: String): Result {
        val uri = URI(link)
        val password = decoded(uri.rawUserInfo)
        require(password.isNotBlank()) { "Trojan password is missing" }
        val query = query(uri.rawQuery)
        val outbound = baseOutbound("trojan", uri, "password", password)
        outbound.put("network", query["network"] ?: "tcp")
        addTls(outbound, uri.host, query, allowReality = false, defaultEnabled = true)
        addTransport(outbound, query)
        return result(outbound, "trojan", label(uri))
    }

    private fun importShadowsocks(link: String): Result {
        val withoutScheme = link.removePrefix("ss://").removePrefix("shadowsocks://")
        val uri = runCatching { URI("ss://$withoutScheme") }.getOrNull()
        var host = uri?.host
        var port = uri?.port ?: -1
        var methodPassword: String
        var display = uri?.rawFragment?.let(::decoded).orEmpty()

        if (!host.isNullOrBlank() && port in 1..65535) {
            val user = decoded(uri.rawUserInfo)
            methodPassword = if (user.contains(':')) user else decodeBase64(user)
        } else {
            val authority = withoutScheme.substringBefore('#').substringBefore('?')
            val decoded = decodeBase64(authority)
            val at = decoded.lastIndexOf('@')
            require(at > 0) { "Shadowsocks share link is malformed" }
            methodPassword = decoded.substring(0, at)
            val serverPart = decoded.substring(at + 1)
            val serverUri = URI("ss://$serverPart")
            host = serverUri.host
            port = serverUri.port
            display = withoutScheme.substringAfter('#', "").let(::decoded)
        }
        val separator = methodPassword.indexOf(':')
        require(separator > 0 && separator < methodPassword.lastIndex) {
            "Shadowsocks method or password is missing"
        }
        require(!host.isNullOrBlank() && port in 1..65535) { "Shadowsocks server is invalid" }
        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", methodPassword.substring(0, separator))
            .put("password", methodPassword.substring(separator + 1))
        return result(outbound, "shadowsocks", display.ifBlank { "Shadowsocks" })
    }

    private fun importSocks(link: String): Result {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "SOCKS5 server address is incomplete"
        }
        val outbound = JSONObject()
            .put("type", "socks")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("version", if (link.startsWith("socks4://")) "4" else "5")
        val userInfo = uri.rawUserInfo?.let(::decoded)
        if (!userInfo.isNullOrBlank()) {
            val split = userInfo.split(":", limit = 2)
            outbound.put("username", split[0])
            if (split.size == 2) outbound.put("password", split[1])
        }
        return result(outbound, "socks5", label(uri))
    }

    private fun baseOutbound(type: String, uri: URI, userKey: String, userValue: String): JSONObject {
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "Proxy server address is incomplete"
        }
        return JSONObject()
            .put("type", type)
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put(userKey, userValue)
    }

    private fun addTls(
        outbound: JSONObject,
        host: String,
        query: Map<String, String>,
        allowReality: Boolean,
        defaultEnabled: Boolean = false
    ) {
        val security = query["security"].orEmpty().lowercase()
        val enabled = when (security) {
            "tls", "reality" -> true
            "none", "" -> defaultEnabled
            else -> throw IllegalArgumentException("Unsupported TLS security mode")
        }
        if (!enabled) return
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", query["sni"]?.takeIf(String::isNotBlank) ?: host)
        query["alpn"]?.takeIf(String::isNotBlank)?.let { value ->
            tls.put("alpn", JSONArray(value.split(',').map(String::trim).filter(String::isNotBlank)))
        }
        query["fp"]?.takeIf(String::isNotBlank)?.let { fingerprint ->
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        }
        if (security == "reality") {
            require(allowReality) { "Reality is not supported for this share-link type" }
            val publicKey = query["pbk"]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Reality public key is missing")
            val reality = JSONObject().put("enabled", true).put("public_key", publicKey)
            query["sid"]?.takeIf(String::isNotBlank)?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        outbound.put("tls", tls)
    }

    private fun addTransport(
        outbound: JSONObject,
        query: Map<String, String>,
        explicitType: String? = null
    ) {
        val type = explicitType ?: query["type"] ?: "tcp"
        val host = query["host"]?.takeIf(String::isNotBlank)
        val path = query["path"]?.takeIf(String::isNotBlank)
        val transport = when (type.lowercase()) {
            "tcp", "none", "" -> null
            "ws", "websocket" -> JSONObject().put("type", "ws").apply {
                path?.let { put("path", it) }
                host?.let { put("headers", JSONObject().put("Host", it)) }
            }
            "grpc" -> JSONObject().put("type", "grpc").apply {
                (query["serviceName"] ?: query["service_name"] ?: path)?.let { put("service_name", it.trim('/')) }
            }
            "http", "h2" -> JSONObject().put("type", "http").apply {
                path?.let { put("path", it) }
                host?.let { put("host", JSONArray().put(it)) }
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").apply {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
            }
            "quic" -> JSONObject().put("type", "quic")
            else -> throw IllegalArgumentException("Unsupported proxy transport")
        }
        if (transport != null) outbound.put("transport", transport)
    }

    private fun result(outbound: JSONObject, protocol: String, name: String): Result {
        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val dnsServer = JSONObject()
            .put("type", "https")
            .put("tag", "netpulse-doh")
            .put("server", "1.1.1.1")
            .put("server_port", 443)
            .put("path", "/dns-query")
            .put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
            .put("detour", "proxy")
        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject()
                .put("servers", JSONArray().put(dnsServer))
                .put("final", "netpulse-doh")
                .put("strategy", "prefer_ipv4"))
            .put("inbounds", JSONArray().put(defaultTunInbound()))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy"))
        return Result(root.toString(), protocol, name.ifBlank { protocol.uppercase() })
    }

    private fun defaultTunInbound(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray()
            .put("172.19.0.1/30")
            .put("fdfe:dcba:9876::1/126"))
        .put("mtu", 1400)
        .put("auto_route", true)
        .put("stack", "mixed")

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            decodeComponent(pieces[0]) to decodeComponent(pieces.getOrElse(1) { "" })
        }.toMap()
    }

    private fun label(uri: URI): String =
        uri.rawFragment?.let(::decoded)?.takeIf(String::isNotBlank) ?: uri.scheme.uppercase()

    private fun decoded(value: String?): String =
        value?.let(::decodeComponent).orEmpty()

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodeBase64(value: String): String {
        val clean = value.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        return ProfileTextDecoder.decodeUtf8(bytes)
    }
}
