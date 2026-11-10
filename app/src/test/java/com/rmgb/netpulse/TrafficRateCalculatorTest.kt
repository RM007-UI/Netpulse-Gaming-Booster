package com.rmgb.netpulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficRateCalculatorTest {
    @Test
    fun firstSampleInitializesCountersWithoutInventingTraffic() {
        val calculator = TrafficRateCalculator()
        val sample = calculator.sample(1024, 2048, 1000)!!
        assertEquals(0L, sample.downloadBytesPerSecond)
        assertEquals(0L, sample.uploadBytesPerSecond)
    }

    @Test
    fun computesRatesFromMonotonicCounterDelta() {
        val calculator = TrafficRateCalculator()
        calculator.sample(10_000, 20_000, 1_000)
        val sample = calculator.sample(14_000, 22_000, 3_000)!!
        assertEquals(2_000L, sample.downloadBytesPerSecond)
        assertEquals(1_000L, sample.uploadBytesPerSecond)
    }

    @Test
    fun counterResetProducesZeroForThatIntervalAndRebases() {
        val calculator = TrafficRateCalculator()
        calculator.sample(10_000, 20_000, 1_000)
        val reset = calculator.sample(100, 200, 2_000)!!
        assertEquals(0L, reset.downloadBytesPerSecond)
        assertEquals(0L, reset.uploadBytesPerSecond)
        val after = calculator.sample(1_100, 1_200, 3_000)!!
        assertEquals(1_000L, after.downloadBytesPerSecond)
        assertEquals(1_000L, after.uploadBytesPerSecond)
    }

    @Test
    fun ignoresUnavailableOrNonMonotonicSamples() {
        val calculator = TrafficRateCalculator()
        assertNull(calculator.sample(-1, 100, 0))
        calculator.sample(100, 100, 1_000)
        val sameTime = calculator.sample(200, 200, 1_000)!!
        assertEquals(0L, sameTime.downloadBytesPerSecond)
        val backwards = calculator.sample(300, 300, 900)!!
        assertEquals(0L, backwards.downloadBytesPerSecond)
    }

    @Test
    fun resetClearsSamplingBaseline() {
        val calculator = TrafficRateCalculator()
        calculator.sample(100, 100, 1_000)
        calculator.reset()
        val sample = calculator.sample(500, 500, 2_000)!!
        assertEquals(0L, sample.downloadBytesPerSecond)
        assertEquals(0L, sample.uploadBytesPerSecond)
    }
}
