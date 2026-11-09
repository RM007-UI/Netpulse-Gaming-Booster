package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpStunSummaryTest {
    @Test fun computesMedianLossVariationAndSmoothedJitter() {
        val stats = UdpStunSummary.from(listOf(20.0, 24.0, null, 30.0, 26.0))
        assertEquals(5, stats.attempts)
        assertEquals(4, stats.successful)
        assertEquals(25.0, stats.medianMs!!, 0.0)
        assertEquals(20.0, stats.minMs!!, 0.0)
        assertEquals(30.0, stats.maxMs!!, 0.0)
        assertEquals(14.0 / 3.0, stats.variationMs!!, 0.00001)
        assertEquals(4.1171875, stats.jitterMs!!, 0.0000001)
        assertEquals(20.0, stats.failurePercent, 0.0)
    }

    @Test fun allFailedProbesHaveNoLatencyOrJitterAndHundredPercentTimeouts() {
        val stats = UdpStunSummary.from(listOf(null, null, null))
        assertEquals(0, stats.successful)
        assertNull(stats.minMs)
        assertNull(stats.medianMs)
        assertNull(stats.maxMs)
        assertNull(stats.variationMs)
        assertNull(stats.jitterMs)
        assertEquals(100.0, stats.failurePercent, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySampleSet() {
        UdpStunSummary.from(emptyList())
    }
}
