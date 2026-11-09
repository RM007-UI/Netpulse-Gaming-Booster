package com.rmgb.netpulse

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Imports user-supplied sing-box JSON or common proxy share links and creates a TUN profile.
 * The caller must validate the resulting JSON with Libbox.checkConfig before saving it.
 * Credentials are never logged or persisted by this class.
 */
internal object SingBoxProfileImporter {
    data class ImportedProfile(
        val json: String,
        val protocol: String,
        val displayName: String
    )

    fun detectProtocol(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Profile is empty" }
        if (value.startsWith("{")) return "sing-box JSON"
        return when (value.substringBefore("://").lowercase()) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "ss", "shadowsocks" -> "Shadowsocks"
            "socks", "socks5", "socks5h" -> "SOCKS5"
            "hysteria" -> "Hysteria"
            "hysteria2", "hy2" -> "Hysteria2"
            "anytls" -> "AnyTLS"
            "naive", "naive+https" -> "NaiveProxy"
            "tuic" -> "TUIC"
            "http", "https" -> "HTTP proxy"
            else -> throw IllegalArgumentException("Unsupported profile format")
        }
    }

    fun importProfile(input: String): ImportedProfile {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Profile is empty" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 2 * 1024 * 1024) {
            "Profile exceeds the 2 MiB limit"
        }
        if (raw.startsWith("{")) return importJson(raw)
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size == 1) {
            "Paste one share link at a time, or import a sing-box JSON configuration file"
        }
        return importShareLink(lines.single())
    }

    private fun importJson(raw: String): ImportedProfile {
        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("The JSON profile has no outbounds")
        require(outbounds.length() > 0) { "The JSON profile has no outbounds" }

        var protocol = "sing-box"
        var name = "Custom sing-box profile"
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val type = outbound.optString("type")
            if (type.isNotBlank() && type !in setOf("direct", "block", "dns")) {
                protocol = type
                name = outbound.optString("tag").ifBlank { type }
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
        return ImportedProfile(root.toString(), protocol, name)
    }

    private fun importShareLink(link: String): ImportedProfile {
        return when (link.substringBefore("://").lowercase()) {
            "vless" -> importVless(link)
            "vmess" -> importVmess(link)
            "trojan" -> importTrojan(link)
            "ss", "shadowsocks" -> importShadowsocks(link)
            "socks", "socks5", "socks5h" -> importSocks(link)
            "hysteria" -> importHysteria(link)
            "hysteria2", "hy2" -> importHysteria2(link)
            "anytls" -> importAnyTls(link)
            "naive", "naive+https" -> importNaive(link)
            "tuic" -> importTuic(link)
            "http", "https" -> importHttp(link)
            else -> throw IllegalArgumentException("Unsupported profile format")
        }
    }

    private fun importVless(link: String): ImportedProfile {
        val uri = URI(link)
        val uuid = decoded(uri.rawUserInfo).substringBefore(':')
        require(uuid.isNotBlank()) { "VLESS UUID is missing" }
        val params = query(uri.rawQuery)
        val outbound = baseOutbound("vless", uri, "uuid", uuid)
        outbound.put("network", params["network"] ?: params["type"] ?: "tcp")
        params["flow"]?.takeIf(String::isNotBlank)?.let {
            require(it == "xtls-rprx-vision") { "Unsupported VLESS flow" }
            outbound.put("flow", it)
        }
        addTls(outbound, uri.host, params, allowReality = true)
        addTransport(outbound, params)
        return buildProfile(outbound, "VLESS", label(uri))
    }

    private fun importVmess(link: String): ImportedProfile {
        val encoded = link.removePrefix("vmess://").substringBefore('#').trim()
        val source = JSONObject(decodeBase64(encoded))
        val host = source.optString("add")
        val port = source.optString("port").toIntOrNull()
            ?: throw IllegalArgumentException("VMess port is invalid")
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
        val params = mutableMapOf<String, String>()
        source.optString("sni").takeIf(String::isNotBlank)?.let { params["sni"] = it }
        source.optString("host").takeIf(String::isNotBlank)?.let { params["host"] = it }
        source.optString("path").takeIf(String::isNotBlank)?.let { params["path"] = it }
        source.optString("fp").takeIf(String::isNotBlank)?.let { params["fp"] = it }
        source.optString("alpn").takeIf(String::isNotBlank)?.let { params["alpn"] = it }
        if (source.optString("tls").equals("tls", true)) {
            params["security"] = "tls"
            addTls(outbound, host, params, allowReality = false)
        }
        addTransport(outbound, params, explicitType = if (network == "h2") "http" else network)
        return buildProfile(outbound, "VMess", source.optString("ps").ifBlank { "VMess" })
    }

    private fun importTrojan(link: String): ImportedProfile {
        val uri = URI(link)
        val password = decoded(uri.rawUserInfo)
        require(password.isNotBlank()) { "Trojan password is missing" }
        val params = query(uri.rawQuery)
        val outbound = baseOutbound("trojan", uri, "password", password)
        outbound.put("network", params["network"] ?: params["type"] ?: "tcp")
        addTls(outbound, uri.host, params, allowReality = false, defaultEnabled = true)
        addTransport(outbound, params)
        return buildProfile(outbound, "Trojan", label(uri))
    }

    private fun importShadowsocks(link: String): ImportedProfile {
        val raw = link.substringAfter("://").substringBefore('#')
        val withoutQuery = raw.substringBefore('?')
        val uri = runCatching { URI("ss://$raw") }.getOrNull()
        require(query(uri?.rawQuery)["plugin"].isNullOrBlank()) {
            "This Shadowsocks plugin link must be imported as a full sing-box JSON profile"
        }

        var host = uri?.host
        var port = uri?.port ?: -1
        var methodPassword = ""
        if (!host.isNullOrBlank() && port in 1..65535) {
            val user = decoded(uri?.rawUserInfo)
            methodPassword = if (user.contains(':')) user else decodeBase64(user)
        } else {
            val decodedAuthority = decodeBase64(withoutQuery)
            val at = decodedAuthority.lastIndexOf('@')
            require(at > 0) { "Shadowsocks link is malformed" }
            methodPassword = decodedAuthority.substring(0, at)
            val server = URI("ss://" + decodedAuthority.substring(at + 1))
            host = server.host
            port = server.port
        }

        val separator = methodPassword.indexOf(':')
        require(separator > 0 && separator < methodPassword.lastIndex) {
            "Shadowsocks method or password is missing"
        }
        require(!host.isNullOrBlank() && port in 1..65535) { "Shadowsocks server address is invalid" }
        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", methodPassword.substring(0, separator))
            .put("password", methodPassword.substring(separator + 1))
        return buildProfile(outbound, "Shadowsocks", uri?.rawFragment?.let(::decoded).orEmpty())
    }

    private fun importSocks(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "SOCKS server address is incomplete"
        }
        val outbound = JSONObject()
            .put("type", "socks")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("version", if (link.startsWith("socks4://")) "4" else "5")
        val user = uri.rawUserInfo?.let(::decoded)
        if (!user.isNullOrBlank()) {
            val parts = user.split(":", limit = 2)
            outbound.put("username", parts[0])
            if (parts.size == 2) outbound.put("password", parts[1])
        }
        return buildProfile(outbound, "SOCKS5", label(uri))
    }


    /**
     * Hysteria v1 requires explicit bandwidth hints in sing-box. We accept the
     * common share-link spellings but never invent bandwidth values.
     */
    private fun importHysteria(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "Hysteria server address is incomplete"
        }
        val params = query(uri.rawQuery)
        val auth = decoded(uri.rawUserInfo).ifBlank { params["auth"].orEmpty() }
        require(auth.isNotBlank()) { "Hysteria authentication is missing" }

        val upMbps = (params["up_mbps"] ?: params["upmbps"] ?: params["up"])
            ?.toIntOrNull()
        val downMbps = (params["down_mbps"] ?: params["downmbps"] ?: params["down"])
            ?.toIntOrNull()
        require(upMbps != null && upMbps in 1..100000) {
            "Hysteria upload bandwidth (upmbps) is missing or invalid"
        }
        require(downMbps != null && downMbps in 1..100000) {
            "Hysteria download bandwidth (downmbps) is missing or invalid"
        }
        requireSecureTls(params)

        val outbound = JSONObject()
            .put("type", "hysteria")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("auth_str", auth)
            .put("up_mbps", upMbps)
            .put("down_mbps", downMbps)
            .put("tls", tlsObject(uri.host, params))

        val obfs = params["obfs-password"] ?: params["obfs_password"]
            ?: params["obfsParam"] ?: params["obfs_param"] ?: params["obfs"]
        obfs?.takeIf(String::isNotBlank)?.let { outbound.put("obfs", it) }
        params["network"]?.takeIf(String::isNotBlank)?.let { network ->
            require(network in setOf("tcp", "udp")) { "Unsupported Hysteria network type" }
            outbound.put("network", network)
        }
        return buildProfile(outbound, "Hysteria", label(uri))
    }

    private fun importAnyTls(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "AnyTLS server address is incomplete"
        }
        val password = decoded(uri.rawUserInfo)
        require(password.isNotBlank()) { "AnyTLS password is missing" }
        val params = query(uri.rawQuery)
        requireSecureTls(params)

        val outbound = JSONObject()
            .put("type", "anytls")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("password", password)
            .put("tls", tlsObject(uri.host, params))
        params["idle_session_check_interval"]?.takeIf(String::isNotBlank)?.let {
            outbound.put("idle_session_check_interval", it)
        }
        params["idle_session_timeout"]?.takeIf(String::isNotBlank)?.let {
            outbound.put("idle_session_timeout", it)
        }
        params["min_idle_session"]?.toIntOrNull()?.let {
            require(it in 0..64) { "AnyTLS min_idle_session is out of range" }
            outbound.put("min_idle_session", it)
        }
        return buildProfile(outbound, "AnyTLS", label(uri))
    }

    private fun importNaive(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "NaiveProxy server address is incomplete"
        }
        val credentials = decoded(uri.rawUserInfo)
        val parts = credentials.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "NaiveProxy username and password are required"
        }
        val params = query(uri.rawQuery)
        requireSecureTls(params)

        val outbound = JSONObject()
            .put("type", "naive")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("username", parts[0])
            .put("password", parts[1])
            .put("tls", tlsObject(uri.host, params))
        params["quic"]?.let { value ->
            outbound.put("quic", value.equals("1", true) || value.equals("true", true))
        }
        params["quic_congestion_control"]?.takeIf(String::isNotBlank)?.let { algorithm ->
            require(algorithm in setOf("bbr", "bbr2", "cubic", "reno")) {
                "Unsupported NaiveProxy QUIC congestion-control algorithm"
            }
            outbound.put("quic_congestion_control", algorithm)
        }
        return buildProfile(outbound, "NaiveProxy", label(uri))
    }

    /**
     * Reject requests to disable TLS certificate validation on newly imported
     * formats. Users can still use a trusted certificate or correct SNI.
     */
    private fun requireSecureTls(params: Map<String, String>) {
        val insecure = params["insecure"] ?: params["allow_insecure"] ?: params["skip_cert_verify"]
        require(insecure.isNullOrBlank() ||
            !(insecure.equals("1", true) || insecure.equals("true", true) || insecure.equals("yes", true))) {
            "Insecure TLS certificate validation is not accepted"
        }
    }

    private fun importHysteria2(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "Hysteria2 server address is incomplete"
        }
        val params = query(uri.rawQuery)
        val password = decoded(uri.rawUserInfo)
        require(password.isNotBlank()) { "Hysteria2 password is missing" }
        val tls = tlsObject(uri.host, params)
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("password", password)
            .put("tls", tls)
        val obfs = params["obfs"]?.takeIf(String::isNotBlank)
        if (obfs != null) {
            require(obfs == "salamander") { "Unsupported Hysteria2 obfuscation type" }
            val obfsPassword = params["obfs-password"] ?: params["obfs_password"]
            require(!obfsPassword.isNullOrBlank()) { "Hysteria2 obfuscation password is missing" }
            outbound.put("obfs", JSONObject().put("type", obfs).put("password", obfsPassword))
        }
        return buildProfile(outbound, "Hysteria2", label(uri))
    }

    private fun importTuic(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "TUIC server address is incomplete"
        }
        val user = decoded(uri.rawUserInfo)
        val parts = user.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "TUIC UUID and password are required"
        }
        val params = query(uri.rawQuery)
        val outbound = JSONObject()
            .put("type", "tuic")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("uuid", parts[0])
            .put("password", parts[1])
            .put("congestion_control", params["congestion_control"] ?: "cubic")
            .put("udp_relay_mode", params["udp_relay_mode"] ?: "native")
            .put("tls", tlsObject(uri.host, params))
        return buildProfile(outbound, "TUIC", label(uri))
    }

    private fun importHttp(link: String): ImportedProfile {
        val uri = URI(link)
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "HTTP proxy address is incomplete"
        }
        val outbound = JSONObject()
            .put("type", "http")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
        val credentials = uri.rawUserInfo?.let(::decoded)
        if (!credentials.isNullOrBlank()) {
            val parts = credentials.split(":", limit = 2)
            outbound.put("username", parts[0])
            if (parts.size == 2) outbound.put("password", parts[1])
        }
        if (uri.scheme.equals("https", true)) {
            outbound.put("tls", JSONObject().put("enabled", true).put("server_name", uri.host))
        }
        return buildProfile(outbound, "HTTP proxy", label(uri))
    }

    private fun baseOutbound(type: String, uri: URI, key: String, value: String): JSONObject {
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535) {
            "Proxy server address is incomplete"
        }
        return JSONObject()
            .put("type", type)
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put(key, value)
    }

    private fun addTls(
        outbound: JSONObject,
        host: String,
        params: Map<String, String>,
        allowReality: Boolean,
        defaultEnabled: Boolean = false
    ) {
        val security = params["security"].orEmpty().lowercase()
        val enabled = when (security) {
            "tls", "reality" -> true
            "none", "" -> defaultEnabled
            else -> throw IllegalArgumentException("Unsupported TLS security mode")
        }
        if (!enabled) return
        outbound.put("tls", tlsObject(host, params, allowReality))
    }

    private fun tlsObject(
        host: String,
        params: Map<String, String>,
        allowReality: Boolean = false
    ): JSONObject {
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", params["sni"]?.takeIf(String::isNotBlank) ?: host)
        params["alpn"]?.takeIf(String::isNotBlank)?.let { value ->
            tls.put("alpn", JSONArray(value.split(',').map(String::trim).filter(String::isNotBlank)))
        }
        params["fp"]?.takeIf(String::isNotBlank)?.let { fingerprint ->
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        }
        if (params["security"].equals("reality", ignoreCase = true)) {
            require(allowReality) { "Reality is only accepted for VLESS links" }
            val publicKey = params["pbk"]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Reality public key is missing")
            val reality = JSONObject().put("enabled", true).put("public_key", publicKey)
            params["sid"]?.takeIf(String::isNotBlank)?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun addTransport(
        outbound: JSONObject,
        params: Map<String, String>,
        explicitType: String? = null
    ) {
        val type = (explicitType ?: params["type"] ?: "tcp").lowercase()
        val host = params["host"]?.takeIf(String::isNotBlank)
        val path = params["path"]?.takeIf(String::isNotBlank)
        val transport = when (type) {
            "tcp", "none", "" -> null
            "ws", "websocket" -> JSONObject().put("type", "ws").apply {
                path?.let { put("path", it) }
                host?.let { put("headers", JSONObject().put("Host", it)) }
            }
            "grpc" -> JSONObject().put("type", "grpc").apply {
                (params["serviceName"] ?: params["service_name"] ?: path)
                    ?.takeIf(String::isNotBlank)?.let { put("service_name", it.trim('/')) }
            }
            "http", "h2" -> JSONObject().put("type", "http").apply {
                path?.let { put("path", it) }
                host?.let { put("host", JSONArray().put(it)) }
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").apply {
                path?.let { put("path", it) }
                host?.let { put("host", it) }
            }
            else -> throw IllegalArgumentException("Unsupported proxy transport")
        }
        if (transport != null) outbound.put("transport", transport)
    }

    private fun buildProfile(outbound: JSONObject, protocol: String, name: String): ImportedProfile {
        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val dns = JSONObject()
            .put("servers", JSONArray().put(
                JSONObject()
                    .put("type", "https")
                    .put("tag", "netpulse-doh")
                    .put("server", "1.1.1.1")
                    .put("server_port", 443)
                    .put("path", "/dns-query")
                    .put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
                    .put("detour", "proxy")
            ))
            .put("final", "netpulse-doh")
            .put("strategy", "prefer_ipv4")
        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(defaultTunInbound()))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "proxy"))
        return ImportedProfile(root.toString(), protocol, name.ifBlank { protocol })
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
        uri.rawFragment?.let(::decoded)?.takeIf(String::isNotBlank)
            ?: uri.scheme.uppercase()

    private fun decoded(value: String?): String = value?.let(::decodeComponent).orEmpty()

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return ProfileTextDecoder.decodeUtf8(Base64.getDecoder().decode(padded))
    }
}
