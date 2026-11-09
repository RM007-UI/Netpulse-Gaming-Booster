package com.rmgb.netpulse

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale

data class OpenVpnCredentials(val username: String, val password: String) {
    fun validate() {
        require(username.isNotBlank() && username.length <= 256) { "OpenVPN username is missing or too long" }
        require(password.isNotBlank() && password.length <= 1024) { "OpenVPN password is missing or too long" }
    }
}

class OpenVpnCredentialsRequiredException : IllegalArgumentException(
    "This OpenVPN profile requires a username and password"
)

/**
 * Conservative .ovpn importer for sing-box 1.14.x openvpn-client endpoints.
 * Only inline credentials/certificates/keys are accepted. File references,
 * scripts, plugins, compression and unknown routing directives are rejected.
 */
internal object OpenVpnProfileImporter {
    data class ImportedProfile(val json: String, val protocol: String, val displayName: String)

    private data class Remote(val host: String, val port: Int, val network: String)

    fun importProfile(text: String, suppliedCredentials: OpenVpnCredentials? = null): ImportedProfile {
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "OpenVPN profile exceeds the 2 MiB limit"
        }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val blocks = linkedMapOf<String, String>()
        val directives = mutableListOf<List<String>>()
        var blockName: String? = null
        var blockLines = mutableListOf<String>()

        for (raw in lines) {
            val trimmed = raw.trim()
            val opening = INLINE_OPEN.matchEntire(trimmed)
            if (blockName == null && opening != null) {
                blockName = opening.groupValues[1].lowercase(Locale.ROOT)
                require(blockName !in blocks) { "Duplicate inline <$blockName> block" }
                blockLines = mutableListOf()
                continue
            }
            if (blockName != null) {
                if (trimmed.equals("</$blockName>", ignoreCase = true)) {
                    blocks[blockName] = blockLines.joinToString("\n").trim()
                    blockName = null
                    blockLines = mutableListOf()
                } else {
                    blockLines += raw
                }
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val tokens = tokenize(trimmed)
            if (tokens.isNotEmpty()) directives += tokens
        }
        require(blockName == null) { "OpenVPN inline <$blockName> block is not closed" }

        val remotes = mutableListOf<Remote>()
        var defaultNetwork = "udp"
        var remoteRandom = false
        var redirectGateway = false
        var routeNoPull = false
        var blockIpv6 = false
        var keyDirection = ""
        var username = ""
        var password = ""
        var ca: String? = blocks["ca"]?.takeIf(String::isNotBlank)
        val clientCert = blocks["cert"]?.takeIf(String::isNotBlank)
        val clientKey = blocks["key"]?.takeIf(String::isNotBlank)
        var controlWrapType: String? = null
        var controlWrapKey: String? = null
        var inlineAuth = blocks["auth-user-pass"]?.lines()?.map(String::trim)?.filter(String::isNotEmpty)
        var verifyName: String? = null
        var verifyNameType = "name"
        var tlsVersionMin = "1.2"
        var tlsCipher = ""
        var auth = ""
        var mssFix = 0
        var mtu = 1500
        var pingInterval = ""
        var pingRestart = ""
        var renegotiate = ""
        var renegotiateDisabled = false
        var explicitExitNotify = 0
        var routeMetric: Int? = null
        var dataCiphers = emptyList<String>()
        var dataCipherFallback = ""
        var authUserPassRequired = false
        val redirectFlags = linkedSetOf<String>()
        val explicitRoutes = linkedSetOf<String>()
        val pullFilters = JSONArray()

        require(!blocks["pkcs12"].isNullOrBlank()) {
            "PKCS#12 client bundles must be converted to inline <cert> and <key> blocks before import"
        }
        require(blocks["secret"].isNullOrBlank()) {
            "Static-key OpenVPN profiles are not accepted; import a TLS-mode .ovpn profile"
        }
        require(blocks["auth-user-pass"].isNullOrBlank() || inlineAuth?.size == 2) {
            "Inline <auth-user-pass> must contain exactly username and password"
        }

        fun one(tokens: List<String>, count: Int, directive: String) {
            require(tokens.size == count) { "OpenVPN directive '$directive' has an unsupported form" }
        }

        for (tokens in directives) {
            val key = tokens.first().lowercase(Locale.ROOT)
            val args = tokens.drop(1)
            when (key) {
                "client", "tls-client", "dev", "persist-key", "persist-tun",
                "nobind", "resolv-retry", "auth-nocache", "remote-cert-tls" -> {
                    if (key == "dev") require(args.firstOrNull()?.equals("tun", true) == true) {
                        "Only TUN-mode OpenVPN profiles are supported"
                    }
                    if (key == "remote-cert-tls") require(args.firstOrNull()?.equals("server", true) == true) {
                        "OpenVPN profile must verify a server certificate"
                    }
                }
                "proto" -> {
                    one(tokens, 2, key)
                    defaultNetwork = normalizeNetwork(args[0])
                }
                "remote" -> {
                    require(args.size in 1..3) { "OpenVPN remote directive is invalid" }
                    val port = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                    require(port in 1..65535) { "OpenVPN remote port is invalid" }
                    val network = args.getOrNull(2)?.let(::normalizeNetwork) ?: defaultNetwork
                    remotes += Remote(args[0], port, network)
                }
                "remote-random" -> {
                    one(tokens, 1, key)
                    remoteRandom = true
                }
                "auth-user-pass" -> {
                    require(args.isEmpty()) {
                        "External auth-user-pass files are not supported; use the in-app credential prompt"
                    }
                    authUserPassRequired = true
                }
                "key-direction" -> {
                    one(tokens, 2, key)
                    keyDirection = normalizeKeyDirection(args[0])
                }
                "tls-auth", "tls-crypt", "tls-crypt-v2" -> {
                    val block = blocks[key]?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("Inline <$key> block is missing")
                    if (args.isNotEmpty()) {
                        require(args.first() == "[inline]" && args.size <= 2) {
                            "External $key key files are not supported; use an inline <$key> block"
                        }
                        if (key == "tls-auth" && args.size == 2) {
                            keyDirection = normalizeKeyDirection(args[1])
                        } else {
                            require(args.size == 1 || key == "tls-auth") { "Unexpected arguments for $key" }
                        }
                    }
                    if (controlWrapKey == null) {
                        controlWrapType = when (key) {
                            "tls-auth" -> "tls_auth"
                            "tls-crypt" -> "tls_crypt"
                            else -> "tls_crypt_v2"
                        }
                        controlWrapKey = block
                    } else {
                        require(controlWrapType == when (key) {
                            "tls-auth" -> "tls_auth"
                            "tls-crypt" -> "tls_crypt"
                            else -> "tls_crypt_v2"
                        }) { "Conflicting TLS control-wrap configuration" }
                    }
                }
                "verify-x509-name" -> {
                    require(args.size in 1..2) { "verify-x509-name is invalid" }
                    verifyName = args[0]
                    verifyNameType = when (args.getOrNull(1)?.lowercase(Locale.ROOT) ?: "name") {
                        "name" -> "name"
                        "name-prefix" -> "name-prefix"
                        "subject" -> "subject"
                        else -> throw IllegalArgumentException("Unsupported verify-x509-name mode")
                    }
                }
                "tls-version-min" -> {
                    one(tokens, 2, key)
                    tlsVersionMin = args[0]
                    require(tlsVersionMin in setOf("1.2", "1.3")) {
                        "OpenVPN TLS 1.0/1.1 minimums are rejected; TLS 1.2 or newer is required"
                    }
                }
                "tls-cipher" -> {
                    require(args.isNotEmpty()) { "tls-cipher requires at least one cipher name" }
                    tlsCipher = args.joinToString(" ")
                }
                "data-ciphers" -> {
                    require(args.isNotEmpty()) { "data-ciphers is empty" }
                    dataCiphers = args.joinToString("").split(':', ',').map(String::trim).filter(String::isNotEmpty)
                    require(dataCiphers.isNotEmpty()) { "data-ciphers is invalid" }
                }
                "data-ciphers-fallback", "cipher" -> {
                    one(tokens, 2, key)
                    dataCipherFallback = args[0]
                }
                "auth" -> {
                    one(tokens, 2, key)
                    auth = args[0]
                }
                "tun-mtu" -> {
                    one(tokens, 2, key)
                    mtu = args[0].toIntOrNull() ?: throw IllegalArgumentException("tun-mtu is invalid")
                    require(mtu in 1280..9000) { "tun-mtu must be between 1280 and 9000" }
                }
                "mssfix" -> {
                    require(args.isNotEmpty()) { "mssfix value is missing" }
                    mssFix = args[0].toIntOrNull()
                        ?: throw IllegalArgumentException("mssfix value is invalid")
                    require(mssFix in 576..9000) { "mssfix value is out of range" }
                }
                "redirect-gateway" -> {
                    redirectGateway = true
                    args.forEach { flag ->
                        require(flag in setOf("def1", "ipv6", "!ipv4", "local", "autolocal", "bypass-dhcp", "bypass-dns")) {
                            "Unsupported redirect-gateway flag: $flag"
                        }
                        if (flag in setOf("def1", "ipv6", "!ipv4")) redirectFlags += flag
                    }
                }
                "route" -> {
                    require(args.size in 2..4) { "OpenVPN route directive is incomplete" }
                    require(args.getOrNull(2).isNullOrBlank() || args[2].equals("vpn_gateway", true)) {
                        "OpenVPN route gateway override is not supported"
                    }
                    args.getOrNull(3)?.let { rawMetric ->
                        val metric = rawMetric.toIntOrNull()
                            ?: throw IllegalArgumentException("OpenVPN route metric is invalid")
                        require(metric in 0..65535) { "OpenVPN route metric is out of range" }
                        require(routeMetric == null || routeMetric == metric) {
                            "Per-route metrics cannot be represented by this OpenVPN endpoint"
                        }
                        routeMetric = metric
                    }
                    val prefix = ipv4NetworkPrefix(args[0], args[1])
                    explicitRoutes += prefix
                }
                "route-ipv6" -> {
                    one(tokens, 2, key)
                    explicitRoutes += normalizeIpv6Prefix(args[0])
                }
                "route-nopull" -> {
                    one(tokens, 1, key)
                    routeNoPull = true
                }
                "block-ipv6" -> {
                    one(tokens, 1, key)
                    blockIpv6 = true
                }
                "pull-filter" -> {
                    require(args.size >= 2) { "pull-filter requires an action and option prefix" }
                    val action = args[0].lowercase(Locale.ROOT)
                    require(action in setOf("accept", "ignore", "reject")) { "Invalid pull-filter action" }
                    pullFilters.put(JSONObject().put("action", action).put("text", args.drop(1).joinToString(" ")))
                }
                "ping" -> {
                    one(tokens, 2, key)
                    pingInterval = durationSeconds(args[0], key)
                }
                "ping-restart" -> {
                    one(tokens, 2, key)
                    pingRestart = durationSeconds(args[0], key)
                }
                "reneg-sec" -> {
                    one(tokens, 2, key)
                    if (args[0] == "0") renegotiateDisabled = true
                    else renegotiate = durationSeconds(args[0], key)
                }
                "explicit-exit-notify" -> {
                    require(args.size <= 1) { "explicit-exit-notify accepts at most one value" }
                    explicitExitNotify = args.singleOrNull()?.toIntOrNull() ?: if (args.isEmpty()) 1 else {
                        throw IllegalArgumentException("explicit-exit-notify is invalid")
                    }
                    require(explicitExitNotify in 0..10) { "explicit-exit-notify is out of range" }
                }
                "comp-lzo", "compress" -> {
                    val mode = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "yes"
                    require(mode in setOf("no", "none", "stub", "stub-v2", "off", "disabled")) {
                        "OpenVPN compression is rejected for security and compatibility"
                    }
                }
                "allow-compression" -> {
                    one(tokens, 2, key)
                    require(args[0].equals("no", true)) {
                        "OpenVPN compression must remain disabled"
                    }
                }
                "verb", "mute", "float", "connect-retry",
                "connect-retry-max", "server-poll-timeout", "hand-window", "tls-exit",
                "persist-local-ip", "persist-remote-ip", "remote-random-hostname",
                "allow-pull-fqdn", "auth-retry", "setenv", "setenv-safe" -> {
                    // Harmless client metadata / retry preferences not needed by sing-box.
                    if (key == "setenv" || key == "setenv-safe") {
                        require(args.none { it.contains("script", true) }) { "Script-related environment values are not supported" }
                    }
                }
                "ca", "cert", "key", "pkcs12" -> {
                    require(args.isEmpty()) { "External $key file paths are not supported; use an inline <$key> block" }
                }
                "script-security", "up", "down", "route-up", "route-pre-down", "ipchange",
                "plugin", "management", "management-client", "management-query-passwords",
                "askpass", "config", "include", "cd", "chroot", "user", "group",
                "daemon", "writepid", "log", "log-append", "status", "status-version",
                "dhcp-option", "ifconfig", "ifconfig-ipv6", "ifconfig-pool", "ifconfig-pool-persist" -> {
                    throw IllegalArgumentException("OpenVPN directive '$key' needs platform-specific or external-file behavior and is not imported")
                }
                else -> throw IllegalArgumentException("Unsupported OpenVPN directive '$key'; import a compatible .ovpn file")
            }
        }

        // Inline key blocks are themselves the TLS control-wrap directive in standard .ovpn files.
        // This also handles profiles where "tls-auth ta.key" was replaced with <tls-auth>.
        if (controlWrapKey == null) {
            val inlineWrap = listOf("tls-auth", "tls-crypt", "tls-crypt-v2")
                .firstOrNull { !blocks[it].isNullOrBlank() }
            if (inlineWrap != null) {
                controlWrapType = when (inlineWrap) {
                    "tls-auth" -> "tls_auth"
                    "tls-crypt" -> "tls_crypt"
                    else -> "tls_crypt_v2"
                }
                controlWrapKey = blocks[inlineWrap]
            }
        }

        require(remotes.isNotEmpty()) { "OpenVPN profile has no remote server" }
        require(ca != null) { "OpenVPN TLS profile must include an inline <ca> certificate" }
        require((clientCert == null) == (clientKey == null)) {
            "OpenVPN inline <cert> and <key> must be provided together"
        }
        require(!routeNoPull || redirectGateway || explicitRoutes.isNotEmpty()) {
            "route-nopull is set but the profile has no explicit routes"
        }

        if (inlineAuth != null) {
            username = inlineAuth[0]
            password = inlineAuth[1]
        } else if (authUserPassRequired) {
            val credentials = suppliedCredentials ?: throw OpenVpnCredentialsRequiredException()
            credentials.validate()
            username = credentials.username
            password = credentials.password
        } else {
            require(suppliedCredentials == null) { "This profile does not request username/password authentication" }
        }

        val tls = JSONObject()
            .put("certificate", JSONArray().put(ca))
            .put("remote_certificate_tls", "server")
            .put("version_min", tlsVersionMin)
        if (clientCert != null && clientKey != null) {
            tls.put("client_certificate", JSONArray().put(clientCert))
            tls.put("client_key", JSONArray().put(clientKey))
        }
        if (verifyName != null) {
            tls.put("server_name", verifyName)
            tls.put("server_name_type", verifyNameType)
        }
        if (tlsCipher.isNotBlank()) tls.put("cipher", tlsCipher)
        if (controlWrapKey != null) {
            val controlWrap = JSONObject()
                .put("type", controlWrapType)
                .put("key", JSONArray().put(controlWrapKey))
            if (controlWrapType == "tls_auth" && keyDirection.isNotBlank()) {
                controlWrap.put("direction", keyDirection)
            }
            tls.put("control_wrap", controlWrap)
        } else {
            require(keyDirection.isBlank()) { "key-direction requires tls-auth" }
        }

        val endpoint = JSONObject()
            .put("type", "openvpn-client")
            .put("tag", "netpulse-openvpn")
            .put("mode", "tls")
            .put("servers", JSONArray().apply {
                remotes.forEach { remote ->
                    put(JSONObject()
                        .put("server", remote.host)
                        .put("server_port", remote.port)
                        .put("network", remote.network))
                }
            })
            .put("network", remotes.first().network)
            .put("remote_random", remoteRandom)
            .put("tls", tls)
            .put("redirect_gateway", redirectGateway)
            .put("redirect_gateway_flags", JSONArray(redirectFlags.toList()))
            .put("route_no_pull", routeNoPull)
            // If the profile captures IPv4 default traffic but does not provide an
            // IPv6 tunnel route, block IPv6 rather than silently leaking it outside VPN.
            .put(
                "block_ipv6",
                blockIpv6 || (redirectGateway && "ipv6" !in redirectFlags && "!ipv4" !in redirectFlags)
            )
            .put("mtu", mtu)

        if (username.isNotBlank()) endpoint.put("username", username)
        if (password.isNotBlank()) endpoint.put("password", password)
        if (dataCiphers.isNotEmpty()) endpoint.put("data_ciphers", JSONArray(dataCiphers))
        if (dataCipherFallback.isNotBlank()) endpoint.put("data_ciphers_fallback", dataCipherFallback)
        if (auth.isNotBlank()) endpoint.put("auth", auth)
        if (mssFix > 0) endpoint.put("mss_fix", mssFix)
        if (pingInterval.isNotBlank()) endpoint.put("ping_interval", pingInterval)
        if (pingRestart.isNotBlank()) endpoint.put("ping_restart", pingRestart)
        if (renegotiate.isNotBlank()) endpoint.put("renegotiate_interval", renegotiate)
        if (renegotiateDisabled) endpoint.put("renegotiate_disabled", true)
        if (explicitExitNotify > 0) endpoint.put("explicit_exit_notify", explicitExitNotify)
        if (explicitRoutes.isNotEmpty()) endpoint.put("routes", JSONArray(explicitRoutes.toList()))
        routeMetric?.let { endpoint.put("route_metric", it) }
        if (pullFilters.length() > 0) endpoint.put("pull_filters", pullFilters)

        val json = JSONObject()
            .put("inbounds", JSONArray().put(defaultTunInbound()))
            .put("endpoints", JSONArray().put(endpoint))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "direct").put("auto_detect_interface", true))
        val protocol = if (remotes.all { it.network == "tcp" }) "OpenVPN TCP" else "OpenVPN UDP"
        val displayName = remotes.first().host
        return ImportedProfile(json.toString(), protocol, displayName)
    }

    private fun defaultTunInbound(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
        .put("mtu", 1400)
        .put("auto_route", true)
        .put("stack", "mixed")

    private fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (char in line) {
            if (escaped) {
                token.append(char)
                escaped = false
                continue
            }
            if (char == '\\' && quote != null) {
                escaped = true
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null else token.append(char)
            } else if (char == '"' || char == '\'') {
                quote = char
            } else if (char.isWhitespace()) {
                if (token.isNotEmpty()) {
                    result += token.toString()
                    token.setLength(0)
                }
            } else if (char == '#' || char == ';') {
                break
            } else {
                token.append(char)
            }
        }
        require(quote == null && !escaped) { "OpenVPN directive has an unclosed quoted value" }
        if (token.isNotEmpty()) result += token.toString()
        return result
    }

    private fun normalizeNetwork(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "udp", "udp4", "udp6" -> "udp"
        "tcp", "tcp-client", "tcp4-client", "tcp6-client" -> "tcp"
        else -> throw IllegalArgumentException("Only OpenVPN UDP or TCP client transport is supported")
    }

    private fun normalizeKeyDirection(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "0", "server" -> "server"
        "1", "client" -> "client"
        else -> throw IllegalArgumentException("OpenVPN key-direction must be 0 or 1")
    }

    private fun durationSeconds(value: String, directive: String): String {
        val seconds = value.removeSuffix("s").toIntOrNull()
            ?: throw IllegalArgumentException("OpenVPN $directive must be specified in seconds")
        require(seconds in 1..86400) { "OpenVPN $directive is out of range" }
        return "${seconds}s"
    }

    private fun ipv4NetworkPrefix(address: String, netmask: String): String {
        val ip = InetAddress.getByName(address).address
        val mask = InetAddress.getByName(netmask).address
        require(ip.size == 4 && mask.size == 4) { "OpenVPN route must use an IPv4 network and netmask" }
        val bits = mask.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(2).padStart(8, '0')
        }
        require(!bits.contains("01")) { "OpenVPN route netmask is not contiguous" }
        val prefix = bits.count { it == '1' }
        return "$address/$prefix"
    }

    private fun normalizeIpv6Prefix(value: String): String {
        require(value.contains('/')) { "OpenVPN IPv6 route must include a prefix length" }
        val parts = value.split('/', limit = 2)
        val address = InetAddress.getByName(parts[0])
        require(address.address.size == 16) { "OpenVPN IPv6 route address is invalid" }
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..128) { "OpenVPN IPv6 route prefix is invalid" }
        return "${address.hostAddress.substringBefore('%')}/$prefix"
    }

    private val INLINE_OPEN = Regex("^<([A-Za-z0-9_-]+)>$", RegexOption.IGNORE_CASE)
    private const val DEFAULT_PORT = 1194
}
