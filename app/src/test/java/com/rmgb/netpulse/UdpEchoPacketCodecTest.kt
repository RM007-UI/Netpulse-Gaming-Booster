package com.rmgb.netpulse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UdpEchoPacketCodecTest {
    @Test
    fun parsesHexPayloadAndBuildsUniqueNonceEnvelopes() {
        val payload = UdpEchoPacketCodec.parsePayloadHex("01 02 aF")
        assertArrayEquals(byteArrayOf(1, 2, 0xaf.toByte()), payload)
        val first = UdpEchoPacketCodec.buildRequest(payload)
        val second = UdpEchoPacketCodec.buildRequest(payload)
        assertNotEquals(first.toList(), second.toList())
        assertTrue(first.size > payload.size)
        assertTrue(UdpEchoPacketCodec.isMatchingEcho(first, first.copyOf(), first.size))
    }

    @Test
    fun rejectsMalformedAndOversizedHexPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            UdpEchoPacketCodec.parsePayloadHex("0xz1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UdpEchoPacketCodec.parsePayloadHex("123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UdpEchoPacketCodec.parsePayloadHex("aa".repeat(49))
        }
    }

    @Test
    fun rejectsTruncatedOrModifiedEchoes() {
        val request = UdpEchoPacketCodec.buildRequest(byteArrayOf(1, 2, 3))
        assertFalse(UdpEchoPacketCodec.isMatchingEcho(request, request.copyOf(), request.size - 1))
        val changed = request.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(UdpEchoPacketCodec.isMatchingEcho(request, changed, changed.size))
    }
}
