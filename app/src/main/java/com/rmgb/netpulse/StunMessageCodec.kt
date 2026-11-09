package com.rmgb.netpulse

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RFC 8489 STUN Binding message codec used by the UDP path diagnostic. */
object StunMessageCodec {
    const val HEADER_SIZE = 20
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS_RESPONSE = 0x0101
    private const val MAGIC_COOKIE = 0x2112A442

    fun createBindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12) { "STUN transaction ID must be exactly 96 bits" }
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putShort(BINDING_REQUEST.toShort())
            .putShort(0)
            .putInt(MAGIC_COOKIE)
            .put(transactionId)
            .array()
    }

    fun isMatchingBindingSuccess(
        message: ByteArray,
        offset: Int,
        length: Int,
        transactionId: ByteArray
    ): Boolean {
        if (transactionId.size != 12 || offset < 0 || length < HEADER_SIZE) return false
        if (offset > message.size || length > message.size - offset) return false

        val buffer = ByteBuffer.wrap(message, offset, length).slice().order(ByteOrder.BIG_ENDIAN)
        val messageType = buffer.short.toInt() and 0xffff
        val bodyLength = buffer.short.toInt() and 0xffff
        val cookie = buffer.int

        if (messageType != BINDING_SUCCESS_RESPONSE) return false
        if (cookie != MAGIC_COOKIE || bodyLength % 4 != 0) return false
        if (HEADER_SIZE + bodyLength != length) return false

        for (index in transactionId.indices) {
            if (buffer.get(8 + index) != transactionId[index]) return false
        }
        return true
    }
}
