package com.rmgb.netpulse

import android.net.Ikev2VpnProfile
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

enum class Ikev2AuthMethod { EAP_MSCHAPV2, PRE_SHARED_KEY }

/**
 * Native Android IKEv2/IPsec profile. Credentials are serialized only into the
 * encrypted SecureProfileStore; they are not exposed through the WebView bridge.
 */
data class Ikev2ProfileConfig(
    val serverAddress: String,
    val identity: String,
    val authMethod: Ikev2AuthMethod,
    val username: String = "",
    val password: String = "",
    val preSharedKey: String = "",
    val serverRootCaPem: String = "",
    val displayName: String = "IKEv2 connection"
) {
    fun validate() {
        require(serverAddress.isNotBlank() && serverAddress.length <= 253) { "IKEv2 server address is missing or too long" }
        require(serverAddress.none(Char::isWhitespace) && !serverAddress.contains("://") &&
            !serverAddress.contains('/') && !serverAddress.contains('@')) {
            "Enter a hostname or IP address, not a URL"
        }
        require(identity.isNotBlank() && identity.length <= 255 && identity.none(Char::isWhitespace)) {
            "IKE identity is missing or invalid"
        }
        require(displayName.length <= 80) { "IKEv2 profile name is too long" }
        when (authMethod) {
            Ikev2AuthMethod.EAP_MSCHAPV2 -> {
                require(username.isNotBlank() && username.length <= 256) { "IKEv2 EAP username is required" }
                require(password.isNotBlank() && password.length <= 1024) { "IKEv2 EAP password is required" }
                require(preSharedKey.isEmpty()) { "Do not combine EAP credentials with a PSK" }
            }
            Ikev2AuthMethod.PRE_SHARED_KEY -> {
                require(preSharedKey.length in 8..512) { "IKEv2 pre-shared key must contain between 8 and 512 characters" }
                require(username.isEmpty() && password.isEmpty()) { "Do not combine a PSK with EAP credentials" }
            }
        }
        if (serverRootCaPem.isNotBlank()) parseRootCertificate(serverRootCaPem)
    }

    fun toJson(): String = JSONObject()
        .put("server", serverAddress)
        .put("identity", identity)
        .put("auth_method", authMethod.name)
        .put("username", username)
        .put("password", password)
        .put("psk", preSharedKey)
        .put("server_root_ca", serverRootCaPem)
        .put("display_name", displayName)
        .toString()

    @RequiresApi(Build.VERSION_CODES.R)
    fun toPlatformProfile(): Ikev2VpnProfile {
        validate()
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Native IKEv2 requires Android 11 or newer"
        }
        val rootCa = serverRootCaPem.takeIf(String::isNotBlank)?.let(::parseRootCertificate)
        val builder = Ikev2VpnProfile.Builder(serverAddress, identity)
        when (authMethod) {
            Ikev2AuthMethod.EAP_MSCHAPV2 ->
                builder.setAuthUsernamePassword(username, password, rootCa)
            Ikev2AuthMethod.PRE_SHARED_KEY ->
                builder.setAuthPsk(preSharedKey.toByteArray(Charsets.UTF_8))
        }
        builder.setBypassable(false)
            .setLocalRoutesExcluded(false)
            .setMaxMtu(1400)
        if (Build.VERSION.SDK_INT >= 33) builder.setRequiresInternetValidation(true)
        return builder.build()
    }

    companion object {
        fun fromJson(raw: String): Ikev2ProfileConfig {
            val json = JSONObject(raw)
            val config = Ikev2ProfileConfig(
                serverAddress = json.getString("server"),
                identity = json.getString("identity"),
                authMethod = Ikev2AuthMethod.valueOf(json.getString("auth_method")),
                username = json.optString("username"),
                password = json.optString("password"),
                preSharedKey = json.optString("psk"),
                serverRootCaPem = json.optString("server_root_ca"),
                displayName = json.optString("display_name").ifBlank { "IKEv2 connection" }
            )
            config.validate()
            return config
        }

        internal fun parseRootCertificate(pem: String): X509Certificate {
            require(pem.contains("-----BEGIN CERTIFICATE-----") &&
                pem.contains("-----END CERTIFICATE-----")) {
                "Root CA must be a PEM-encoded X.509 certificate"
            }
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
        }
    }
}
