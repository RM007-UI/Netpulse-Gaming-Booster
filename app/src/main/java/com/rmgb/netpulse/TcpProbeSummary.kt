package com.rmgb.netpulse

import kotlin.math.abs

data class TcpProbeStats(
    val attempts: Int,
    val successful: Int,
    val minMs: Double?,
    val medianMs: Double?,
    val maxMs: Double?,
    val variationMs: Double?,
    val failurePercent: Double
)

/**
 * Summarizes TCP connection-establishment samples. This is not ICMP ping,
 * UDP game-server latency, or packet-loss measurement.
 */
object TcpProbeSummary {
    fun from(samples: List<Long?>): TcpProbeStats {
        require(samples.isNotEmpty()) { "At least one TCP probe sample is required" }
        val successes = samples.filterNotNull()
        val sorted = successes.sorted()
        val median = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2].toDouble()
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val variation = if (successes.size < 2) null else {
            successes.zipWithNext { first, second -> abs(second - first).toDouble() }.average()
        }
        return TcpProbeStats(
            attempts = samples.size,
            successful = successes.size,
            minMs = sorted.firstOrNull()?.toDouble(),
            medianMs = median,
            maxMs = sorted.lastOrNull()?.toDouble(),
            variationMs = variation,
            failurePercent = (samples.size - successes.size) * 100.0 / samples.size
        )
    }
}
