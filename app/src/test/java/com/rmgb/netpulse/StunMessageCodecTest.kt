package com.rmgb.netpulse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StunMessageCodecTest {
    private val transaction = ByteArray(12) { (it + 1).toByte() }

    @Test fun createsRfc8489BindingRequestHeader() {
        val request = StunMessageCodec.createBindingRequest(transaction)
        val buffer = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN)
        assertEquals(20, request.size)
        assertEquals(0x0001, buffer.short.toInt() and 0xffff)
        assertEquals(0, buffer.short.toInt() and 0xffff)
        assertEquals(0x2112A442, buffer.int)
        val actualTransaction = ByteArray(12)
        buffer.get(actualTransaction)
        assertTrue(actualTransaction.contentEquals(transaction))
    }

    @Test fun acceptsOnlyMatchingBindingSuccessResponse() {
        val response = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x0101.toShort())
            .putShort(0)
            .putInt(0x2112A442)
            .put(transaction)
            .array()
        assertTrue(StunMessageCodec.isMatchingBindingSuccess(response, 0, response.size, transaction))
    }

    @Test fun rejectsWrongTypeCookieTransactionAndTruncatedPackets() {
        val valid = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x0101.toShort()).putShort(0).putInt(0x2112A442).put(transaction).array()
        assertFalse(StunMessageCodec.isMatchingBindingSuccess(valid, 0, 19, transaction))

        val wrongType = valid.copyOf().also { it[1] = 0x02 }
        assertFalse(StunMessageCodec.isMatchingBindingSuccess(wrongType, 0, wrongType.size, transaction))

        val wrongCookie = valid.copyOf().also { it[4] = 0 }
        assertFalse(StunMessageCodec.isMatchingBindingSuccess(wrongCookie, 0, wrongCookie.size, transaction))

        val wrongTransaction = valid.copyOf().also { it[19] = (it[19].toInt() xor 0x01).toByte() }
        assertFalse(StunMessageCodec.isMatchingBindingSuccess(wrongTransaction, 0, wrongTransaction.size, transaction))
    }
}
