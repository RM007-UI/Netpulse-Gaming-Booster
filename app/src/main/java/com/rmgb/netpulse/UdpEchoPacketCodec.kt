package com.rmgb.netpulse

import java.security.SecureRandom

/**
 * NetPulse diagnostic envelope for a UDP echo service.
 * The service must echo the entire datagram byte-for-byte.
 */
object UdpEchoPacketCodec {
    private val magic = byteArrayOf(0x4e, 0x50, 0x47, 0x31) // NPG1
    private const val NONCE_BYTES = 8
    const val MAX_USER_PAYLOAD_BYTES = 48
    private val random = SecureRandom()

    fun parsePayloadHex(rawHex: String): ByteArray {
        val normalized = rawHex.filterNot(Char::isWhitespace)
        require(normalized.length % 2 == 0) { "UDP payload hex must contain pairs of digits" }
        require(normalized.length <= MAX_USER_PAYLOAD_BYTES * 2) {
            "UDP payload is limited to $MAX_USER_PAYLOAD_BYTES bytes"
        }
        require(normalized.all { it.digitToIntOrNull(16) != null }) {
            "UDP payload must be hexadecimal"
        }
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun buildRequest(userPayload: ByteArray): ByteArray {
        require(userPayload.size <= MAX_USER_PAYLOAD_BYTES) {
            "UDP payload is limited to $MAX_USER_PAYLOAD_BYTES bytes"
        }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return magic + nonce + userPayload
    }

    fun isMatchingEcho(request: ByteArray, response: ByteArray, responseLength: Int): Boolean {
        if (responseLength < 0 || responseLength != request.size || responseLength > response.size) return false
        if (request.size < magic.size + NONCE_BYTES) return false
        if (!request.copyOfRange(0, magic.size).contentEquals(magic)) return false
        return request.contentEquals(response.copyOfRange(0, responseLength))
    }
}
