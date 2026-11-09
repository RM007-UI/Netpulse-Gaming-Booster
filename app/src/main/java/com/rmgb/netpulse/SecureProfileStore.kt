package com.rmgb.netpulse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StoredProfiles(
    val wireGuardConfig: String? = null,
    val singBoxProfile: String? = null,
    val singBoxProtocol: String? = null,
    val singBoxDisplayName: String? = null
)

/**
 * Authenticated AES-256-GCM payload format for profiles.
 *
 * Format: magic[4] | version[1] | IV length[1] | IV | ciphertext + 128-bit tag.
 * A fixed AAD value binds ciphertext to this application-specific format.
 */
internal object ProfilePayloadCodec {
    private val magic = byteArrayOf(0x4e, 0x50, 0x53, 0x45) // NPSE
    private const val version: Int = 1
    private const val ivSize = 12
    private const val tagBits = 128
    private val aad = magic + byteArrayOf(version.toByte())

    fun encrypt(plaintext: ByteArray, key: SecretKey, suppliedIv: ByteArray? = null): ByteArray {
        require(suppliedIv == null || suppliedIv.size == ivSize) { "Invalid AES-GCM IV length" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (suppliedIv == null) {
            // Android Keystore generates a fresh unpredictable IV for every encryption.
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(tagBits, suppliedIv))
        }
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: throw IOException("AES-GCM did not return an IV")
        require(iv.size == ivSize) { "Unexpected AES-GCM IV length" }

        return ByteArrayOutputStream(magic.size + 2 + iv.size + encrypted.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(magic)
                output.writeByte(version)
                output.writeByte(iv.size)
                output.write(iv)
                output.write(encrypted)
            }
            buffer.toByteArray()
        }
    }

    fun decrypt(payload: ByteArray, key: SecretKey): ByteArray {
        require(payload.size >= magic.size + 2 + ivSize + 16) { "Encrypted profile file is truncated" }
        require(payload.copyOfRange(0, magic.size).contentEquals(magic)) {
            "Encrypted profile file has an unknown format"
        }
        require((payload[magic.size].toInt() and 0xff) == version) {
            "Encrypted profile format version is unsupported"
        }
        val length = payload[magic.size + 1].toInt() and 0xff
        require(length == ivSize && payload.size > magic.size + 2 + length + 16) {
            "Encrypted profile file has an invalid IV or ciphertext"
        }
        val ivStart = magic.size + 2
        val cipherStart = ivStart + length
        val iv = payload.copyOfRange(ivStart, cipherStart)
        val encrypted = payload.copyOfRange(cipherStart, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tagBits, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    internal fun testKey(bytes: ByteArray): SecretKey =
        SecretKeySpec(bytes.copyOf(), "AES")
}

/**
 * Persists the user's imported VPN profiles encrypted with a non-exportable Android Keystore key.
 * File lives in the application's private no-backup directory. Corrupt or unauthenticated
 * ciphertext is rejected; no plaintext fallback is ever attempted.
 */
class SecureProfileStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "netpulse_profiles.enc"))

    @Synchronized
    fun load(): StoredProfiles? {
        if (!file.baseFile.exists()) return null
        val encrypted = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_ENCRYPTED_BYTES) { "Encrypted profile file exceeds the size limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val jsonBytes = ProfilePayloadCodec.decrypt(encrypted, getOrCreateKey())
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))
        return StoredProfiles(
            wireGuardConfig = json.optStringOrNull("wireguard"),
            singBoxProfile = json.optStringOrNull("singbox"),
            singBoxProtocol = json.optStringOrNull("singbox_protocol"),
            singBoxDisplayName = json.optStringOrNull("singbox_name")
        )
    }

    @Synchronized
    fun save(profiles: StoredProfiles) {
        val json = JSONObject()
            .put("version", versionForJson)
            .put("wireguard", profiles.wireGuardConfig ?: JSONObject.NULL)
            .put("singbox", profiles.singBoxProfile ?: JSONObject.NULL)
            .put("singbox_protocol", profiles.singBoxProtocol ?: JSONObject.NULL)
            .put("singbox_name", profiles.singBoxDisplayName ?: JSONObject.NULL)
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        val encrypted = ProfilePayloadCodec.encrypt(plaintext, getOrCreateKey())
        require(encrypted.size <= MAX_ENCRYPTED_BYTES) { "Profile data exceeds the encrypted storage limit" }

        var stream = file.startWrite()
        try {
            stream.write(encrypted)
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "netpulse.profile.aes256gcm.v1"
        const val MAX_ENCRYPTED_BYTES = 12 * 1024 * 1024
        const val versionForJson = 1
    }
}
