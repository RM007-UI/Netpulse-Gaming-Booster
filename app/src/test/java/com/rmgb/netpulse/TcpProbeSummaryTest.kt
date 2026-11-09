package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TcpProbeSummaryTest {
    @Test fun summarizesMedianRangeVariationAndFailedAttempts() {
        val stats = TcpProbeSummary.from(listOf(20L, 24L, null, 30L, 26L))
        assertEquals(5, stats.attempts)
        assertEquals(4, stats.successful)
        assertEquals(25.0, stats.medianMs!!, 0.0)
        assertEquals(20.0, stats.minMs!!, 0.0)
        assertEquals(30.0, stats.maxMs!!, 0.0)
        assertEquals(7.0, stats.variationMs!!, 0.0)
        assertEquals(20.0, stats.failurePercent, 0.0)
    }

    @Test fun returnsNullLatencyValuesWhenEveryProbeFails() {
        val stats = TcpProbeSummary.from(listOf(null, null, null))
        assertEquals(0, stats.successful)
        assertNull(stats.minMs)
        assertNull(stats.medianMs)
        assertNull(stats.maxMs)
        assertNull(stats.variationMs)
        assertEquals(100.0, stats.failurePercent, 0.0)
    }
}
