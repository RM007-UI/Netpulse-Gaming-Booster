package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException

class ProfileTextDecoderTest {
    @Test
    fun decodesValidUtf8() {
        assertEquals("""{"log":{"level":"info"}}""", ProfileTextDecoder.decodeUtf8("""{"log":{"level":"info"}}""".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfSilentlyReplacingBytes() {
        assertThrows(CharacterCodingException::class.java) {
            ProfileTextDecoder.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }
}
