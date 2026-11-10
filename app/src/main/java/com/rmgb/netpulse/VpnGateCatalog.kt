package com.rmgb.netpulse

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
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

data class VpnGateCatalogDiagnostics(
    val rowsSeen: Int = 0,
    val rowsWithMetadata: Int = 0,
    val profilesChecked: Int = 0,
    val profilesDecoded: Int = 0,
    val profilesAccepted: Int = 0,
    val rejected: Map<String, Int> = emptyMap(),
    val examples: List<String> = emptyList()
)

object VpnGateCatalog {
    const val API_URL = "https://www.vpngate.net/api/iphone/"
    const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 512 * 1024
    private const val MAX_CANDIDATES_TO_VALIDATE = 400
    private const val MAX_RESULTS = 60
    private const val CONNECT_TIMEOUT_MS = 9000
    private const val READ_TIMEOUT_MS = 15000

    @Volatile
    var lastDiagnostics: VpnGateCatalogDiagnostics = VpnGateCatalogDiagnostics()
        private set

    private data class Candidate(
        val host: String,
        val ip: String,
        val country: String,
        val countryCode: String,
        val pingMs: Double?,
        val speedMbps: Double?,
        val sessions: Int,
        val uptimeMinutes: Long,
        val encodedProfile: String
    )

    private val candidateOrder =
        compareBy<Candidate> { it.pingMs ?: Double.MAX_VALUE }
            .thenByDescending { it.speedMbps ?: 0.0 }
            .thenBy { it.sessions }

    fun fetch(): List<VpnGateServer> {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/csv,text/plain")
            connection.setRequestProperty("User-Agent", "NetPulse-Android/0.6.4")
            connection.setRequestProperty("Connection", "close")

            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) {
                "VPN Gate returned HTTP $status. Check the connection and try again."
            }
            require(connection.url.protocol.equals("https", true)) {
                "VPN Gate directory must be fetched over HTTPS."
            }

            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(256 * 1024)
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) {
                        "The VPN Gate list is larger than the safe 16 MiB limit."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.isNotEmpty()) { "VPN Gate returned an empty response." }
            return parse(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    fun parse(csv: String): List<VpnGateServer> {
        require(csv.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "VPN Gate CSV exceeds the 16 MiB limit."
        }

        val lines = csv.replace("\r\n", "\n").replace('\r', '\n').lines()
        val headerLineIndex = lines.indexOfFirst {
            it.trimStart().removePrefix("#").trimStart().startsWith("HostName,", ignoreCase = true)
        }
        require(headerLineIndex >= 0) {
            "VPN Gate response did not contain the expected CSV header."
        }

        val headers = parseCsvLine(lines[headerLineIndex].trim().removePrefix("#"))
            .map { it.trim().lowercase(Locale.ROOT) }
        val index = headers.withIndex().associate { it.value to it.index }
        val required = listOf(
            "hostname", "ip", "ping", "speed", "countrylong", "countryshort",
            "numvpnsessions", "uptime", "openvpn_configdata_base64"
        )
        val missingColumns = required.filterNot(index::containsKey)
        require(missingColumns.isEmpty()) {
            "VPN Gate CSV is missing columns: ${missingColumns.joinToString()}"
        }

        val rejected = linkedMapOf<String, Int>()
        val examples = ArrayList<String>(4)
        fun reject(reason: String, detail: String? = null) {
            rejected[reason] = (rejected[reason] ?: 0) + 1
            if (!detail.isNullOrBlank() && examples.size < 4 && detail !in examples) {
                examples += detail.take(140)
            }
        }

        var rowsSeen = 0
        var rowsWithMetadata = 0
        val candidates = ArrayList<Candidate>(MAX_CANDIDATES_TO_VALIDATE + 1)

        for (line in lines.drop(headerLineIndex + 1)) {
            if (line.isBlank() || line.trimStart().startsWith("*") || line.trimStart().startsWith("#")) continue
            rowsSeen++
            val fieldsResult = runCatching { parseCsvLine(line) }
            if (fieldsResult.isFailure) {
                reject("csv", fieldsResult.exceptionOrNull()?.message)
                continue
            }
            val fields = fieldsResult.getOrThrow()
            fun field(name: String): String = fields.getOrNull(index[name] ?: -1)?.trim().orEmpty()

            val host = field("hostname")
            val ip = field("ip")
            val encoded = field("openvpn_configdata_base64")
            if (!validHost(host) || !validIpLiteral(ip) || encoded.isBlank()) {
                reject("metadata")
                continue
            }
            rowsWithMetadata++

            candidates += Candidate(
                host = host,
                ip = ip,
                country = field("countrylong").ifBlank { field("countryshort") },
                countryCode = field("countryshort"),
                pingMs = field("ping").toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 60000.0 },
                speedMbps = field("speed").toDoubleOrNull()?.takeIf { it > 0.0 }?.div(1_000_000.0),
                sessions = field("numvpnsessions").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                uptimeMinutes = field("uptime").toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                encodedProfile = encoded
            )
            if (candidates.size > MAX_CANDIDATES_TO_VALIDATE) {
                candidates.sortWith(candidateOrder)
                candidates.removeAt(candidates.lastIndex)
            }
        }

        candidates.sortWith(candidateOrder)
        var profilesChecked = 0
        var profilesDecoded = 0
        val accepted = ArrayList<VpnGateServer>(MAX_RESULTS)

        for (candidate in candidates) {
            if (accepted.size >= MAX_RESULTS) break
            profilesChecked++

            val decodedResult = runCatching { Base64.getMimeDecoder().decode(candidate.encodedProfile) }
            if (decodedResult.isFailure) {
                reject("base64", decodedResult.exceptionOrNull()?.message)
                continue
            }
            val decoded = decodedResult.getOrThrow()
            if (decoded.isEmpty() || decoded.size > MAX_CONFIG_BYTES) {
                reject("profile-size")
                continue
            }
            profilesDecoded++

            val rawProfile = decoded.toString(Charsets.UTF_8)
            val profile = ensureInAppUserPass(rawProfile)
            if (!isCompatibleOpenVpnProfile(profile, candidate.ip, candidate.host)) {
                reject("unsupported-profile")
                continue
            }

            val importResult = runCatching {
                OpenVpnProfileImporter.importProfile(profile, OpenVpnCredentials("vpn", "vpn"))
            }
            if (importResult.isFailure) {
                reject("importer", importResult.exceptionOrNull()?.message)
                continue
            }

            accepted += VpnGateServer(
                hostName = candidate.host,
                ipAddress = candidate.ip,
                country = candidate.country,
                countryCode = candidate.countryCode,
                pingMs = candidate.pingMs,
                speedMbps = candidate.speedMbps,
                sessions = candidate.sessions,
                uptimeMinutes = candidate.uptimeMinutes,
                config = profile
            )
        }

        val finalList = accepted.distinctBy { it.id }
            .sortedWith(
                compareBy<VpnGateServer> { it.pingMs ?: Double.MAX_VALUE }
                    .thenByDescending { it.speedMbps ?: 0.0 }
                    .thenBy { it.sessions }
            )
        lastDiagnostics = VpnGateCatalogDiagnostics(
            rowsSeen = rowsSeen,
            rowsWithMetadata = rowsWithMetadata,
            profilesChecked = profilesChecked,
            profilesDecoded = profilesDecoded,
            profilesAccepted = finalList.size,
            rejected = rejected.toMap(),
            examples = examples.toList()
        )
        return finalList
    }

    internal fun ensureInAppUserPass(profile: String): String {
        val lines = profile.replace("\r\n", "\n").replace('\r', '\n').lines().toMutableList()
        var inBlock = false
        var authDirectiveIndex: Int? = null
        var firstInlineBlockIndex: Int? = null

        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!inBlock) {
                if (INLINE_BLOCK_OPEN.matches(trimmed)) {
                    if (firstInlineBlockIndex == null) firstInlineBlockIndex = index
                    inBlock = true
                    continue
                }
                if (trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.isEmpty()) continue
                val directive = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull()
                if (directive.equals("auth-user-pass", ignoreCase = true)) {
                    authDirectiveIndex = index
                    break
                }
            } else if (INLINE_BLOCK_CLOSE.matches(trimmed)) {
                inBlock = false
            }
        }

        if (authDirectiveIndex != null) {
            lines[authDirectiveIndex] = "auth-user-pass"
            return lines.joinToString("\n")
        }
        lines.add(firstInlineBlockIndex ?: lines.size, "auth-user-pass")
        return lines.joinToString("\n")
    }

    private val INLINE_BLOCK_OPEN = Regex("^<[a-zA-Z0-9_-]+>$")
    private val INLINE_BLOCK_CLOSE = Regex("^</[a-zA-Z0-9_-]+>$")

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
            val protocol = match.groupValues.getOrNull(3).orEmpty()
                .ifBlank { defaultProtocol }.lowercase(Locale.ROOT)
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
