package com.rmgb.netpulse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class ProfilePayloadCodecTest {
    private val key = ProfilePayloadCodec.testKey(ByteArray(32) { (it + 1).toByte() })
    private val iv = ByteArray(12) { (it + 11).toByte() }

    @Test
    fun encryptsAndDecryptsProfileBytes() {
        val plain = """{"wireguard":"[Interface]","singbox":"secret-profile"}""".toByteArray()
        val encrypted = ProfilePayloadCodec.encrypt(plain, key, iv)
        assertNotEquals(String(plain), String(encrypted))
        assertArrayEquals(plain, ProfilePayloadCodec.decrypt(encrypted, key))
    }

    @Test
    fun productionEncryptionUsesFreshIvForEachSave() {
        val first = ProfilePayloadCodec.encrypt("same-profile".toByteArray(), key)
        val second = ProfilePayloadCodec.encrypt("same-profile".toByteArray(), key)
        assertNotEquals(first.contentToString(), second.contentToString())
        assertArrayEquals("same-profile".toByteArray(), ProfilePayloadCodec.decrypt(first, key))
        assertArrayEquals("same-profile".toByteArray(), ProfilePayloadCodec.decrypt(second, key))
    }

    @Test
    fun rejectsModifiedCiphertext() {
        val encrypted = ProfilePayloadCodec.encrypt("private-config".toByteArray(), key, iv)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            ProfilePayloadCodec.decrypt(encrypted, key)
        }
    }

    @Test
    fun rejectsWrongKey() {
        val encrypted = ProfilePayloadCodec.encrypt("private-config".toByteArray(), key, iv)
        val otherKey = ProfilePayloadCodec.testKey(ByteArray(32) { (it + 90).toByte() })
        assertThrows(AEADBadTagException::class.java) {
            ProfilePayloadCodec.decrypt(encrypted, otherKey)
        }
    }

    @Test
    fun rejectsUnknownFormatAndTruncatedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfilePayloadCodec.decrypt(byteArrayOf(1, 2, 3), key)
        }
        val encrypted = ProfilePayloadCodec.encrypt("hello".toByteArray(), key, iv)
        encrypted[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            ProfilePayloadCodec.decrypt(encrypted, key)
        }
    }
}
