package com.rmgb.netpulse

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class VpnGateServer(
    val hostName: String,
    val ipAddress: String,
    val country: String,
    val countryCode: String,
    val pingMs: Double?,
    val speedMbps: Double?,
    val sessions: Int,
    val uptimeMinutes: Long,
    val config: String
) {
    val id: String get() = "$hostName|$ipAddress"
}

/**
 * Official VPN Gate public relay directory client.
 *
 * These are volunteer-operated OpenVPN relays, not NetPulse-owned servers.
 * Profiles are decoded from the OpenVPN_ConfigData_Base64 field published by
 * VPN Gate's official CSV API. No server addresses or credentials are bundled.
 */
object VpnGateCatalog {
    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 9000
    private const val READ_TIMEOUT_MS = 12000

    fun fetch(): List<VpnGateServer> {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/csv,text/plain")
            connection.setRequestProperty("User-Agent", "NetPulse-Android/0.6.3")
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) { "VPN Gate API returned HTTP $status; HTTPS redirects are not followed" }
            require(connection.url.protocol.equals("https", true)) { "VPN Gate directory must be fetched over HTTPS" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) { "VPN Gate response exceeds the 12 MiB limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val csv = bytes.toString(Charsets.UTF_8)
            return parse(csv)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(csv: String): List<VpnGateServer> {
        require(csv.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "VPN Gate CSV exceeds the 12 MiB limit"
        }
        val lines = csv.replace("\r\n", "\n").replace('\r', '\n').lines()
        val headerLineIndex = lines.indexOfFirst { it.trimStart().removePrefix("#").startsWith("HostName,") }
        require(headerLineIndex >= 0) { "VPN Gate CSV header was not found" }
        val headers = parseCsvLine(lines[headerLineIndex].trim().removePrefix("#"))
            .map { it.trim().lowercase(Locale.ROOT) }
        val index = headers.withIndex().associate { it.value to it.index }
        val required = listOf(
            "hostname", "ip", "ping", "speed", "countrylong", "countryshort",
            "numvpnsessions", "uptime", "openvpn_configdata_base64"
        )
        required.forEach { require(index.containsKey(it)) { "VPN Gate CSV is missing the $it column" } }

        val rows = ArrayList<VpnGateServer>()
        for (line in lines.drop(headerLineIndex + 1)) {
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue
            val fields = runCatching { parseCsvLine(line) }.getOrNull() ?: continue
            fun field(name: String): String = fields.getOrNull(index[name] ?: -1)?.trim().orEmpty()
            val host = field("hostname")
            val ip = field("ip")
            val country = field("countrylong")
            val countryCode = field("countryshort")
            val encoded = field("openvpn_configdata_base64")
            if (!validHost(host) || !validIpLiteral(ip) || encoded.isBlank()) continue
            val decoded = runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: continue
            if (decoded.isEmpty() || decoded.size > MAX_CONFIG_BYTES) continue
            val profile = decoded.toString(Charsets.UTF_8)
            if (!isCompatibleOpenVpnProfile(profile, ip, host)) continue
            // Do not show rows whose embedded profile the in-app importer cannot parse.
            // This prevents a public directory entry from becoming a broken one-tap option.
            val importable = runCatching {
                OpenVpnProfileImporter.importProfile(profile, OpenVpnCredentials("vpn", "vpn"))
            }.isSuccess
            if (!importable) continue

            val ping = field("ping").toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 60000.0 }
            val speed = field("speed").toDoubleOrNull()?.takeIf { it > 0.0 }?.div(1_000_000.0)
            val sessions = field("numvpnsessions").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val uptime = field("uptime").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            rows += VpnGateServer(
                hostName = host,
                ipAddress = ip,
                country = country.ifBlank { countryCode },
                countryCode = countryCode,
                pingMs = ping,
                speedMbps = speed,
                sessions = sessions,
                uptimeMinutes = uptime,
                config = profile
            )
        }

        return rows.distinctBy { it.id }
            .sortedWith(
                compareBy<VpnGateServer> { it.pingMs ?: Double.MAX_VALUE }
                    .thenByDescending { it.speedMbps ?: 0.0 }
                    .thenBy { it.sessions }
            )
            .take(80)
    }

    private fun isCompatibleOpenVpnProfile(profile: String, expectedIp: String, expectedHost: String): Boolean {
        if (profile.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) return false
        if (!profile.contains("<ca>", ignoreCase = true) ||
            !profile.contains("-----BEGIN CERTIFICATE-----") ||
            !profile.contains("auth-user-pass")
        ) return false
        val defaultProtocol = Regex("(?im)^\\s*proto\\s+(udp|udp4|udp6|tcp|tcp-client|tcp4-client|tcp6-client)\\s*$")
            .find(profile)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
        val remotes = Regex("(?im)^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+(\\S+))?\\s*$")
            .findAll(profile).toList()
        if (remotes.isEmpty()) return false
        return remotes.any { match ->
            val remoteHost = match.groupValues[1].trim().trim('[', ']')
            val port = match.groupValues[2].toIntOrNull() ?: return@any false
            val protocol = match.groupValues.getOrNull(3).orEmpty().ifBlank { defaultProtocol }.lowercase(Locale.ROOT)
            port in 1..65535 &&
                (remoteHost.equals(expectedIp, true) ||
                    remoteHost.equals(expectedHost, true) ||
                    remoteHost.equals("$expectedHost.opengw.net", true)) &&
                protocol in setOf("udp", "udp4", "udp6", "tcp", "tcp-client", "tcp4-client", "tcp6-client")
        }
    }

    private fun validHost(host: String): Boolean =
        host.length in 1..253 && host.matches(Regex("(?i)^[a-z0-9][a-z0-9.-]*[a-z0-9]$"))

    private fun validIpLiteral(value: String): Boolean =
        value.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) &&
            value.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }

    /**
     * RFC-4180 single-record CSV parser: commas inside quoted fields and doubled
     * quotes are preserved. Embedded newlines are not expected in the official API.
     */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        require(!quoted) { "Malformed quoted VPN Gate CSV field" }
        fields += current.toString()
        return fields
    }
}
