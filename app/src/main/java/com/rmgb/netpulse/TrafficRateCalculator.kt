package com.rmgb.netpulse

/**
 * Converts monotonic TrafficStats counter snapshots into byte-per-second rates.
 * Keeps Android UI classes out of the calculation so counter resets and first samples
 * can be tested deterministically.
 */
data class TrafficRateSnapshot(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long
)

class TrafficRateCalculator {
    private var previousRx = -1L
    private var previousTx = -1L
    private var previousAtMs = -1L

    @Synchronized
    fun sample(rxBytes: Long, txBytes: Long, atElapsedMs: Long): TrafficRateSnapshot? {
        if (rxBytes < 0L || txBytes < 0L || atElapsedMs < 0L) return null

        val elapsed = if (previousAtMs >= 0L) atElapsedMs - previousAtMs else 0L
        val download = if (previousRx >= 0L && elapsed > 0L && rxBytes >= previousRx) {
            ((rxBytes - previousRx) * 1000L) / elapsed
        } else 0L
        val upload = if (previousTx >= 0L && elapsed > 0L && txBytes >= previousTx) {
            ((txBytes - previousTx) * 1000L) / elapsed
        } else 0L

        previousRx = rxBytes
        previousTx = txBytes
        previousAtMs = atElapsedMs

        return TrafficRateSnapshot(rxBytes, txBytes, download, upload)
    }

    @Synchronized
    fun reset() {
        previousRx = -1L
        previousTx = -1L
        previousAtMs = -1L
    }
}
