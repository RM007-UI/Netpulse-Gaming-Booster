package com.rmgb.netpulse

import android.net.Network
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.abs

data class UdpStunStats(
    val attempts: Int,
    val successful: Int,
    val minMs: Double?,
    val medianMs: Double?,
    val maxMs: Double?,
    val variationMs: Double?,
    val failurePercent: Double
)

/**
 * Measures UDP request/response time to a public STUN Binding endpoint.
 * The caller supplies the Android Network to pin each socket to, allowing
 * direct-physical and VPN-network measurements to be compared independently.
 */
object UdpStunProbe {
    const val SERVER_HOST = "stun.l.google.com"
    const val SERVER_PORT = 19302
    private const val RESPONSE_BUFFER_BYTES = 1024
    private val random = SecureRandom()

    fun measure(
        network: Network,
        serverAddress: InetAddress,
        attempts: Int = 5,
        timeoutMs: Int = 1200,
        pauseMs: Long = 80L
    ): UdpStunStats {
        require(attempts in 1..10) { "UDP probe attempts must be between 1 and 10" }
        require(timeoutMs in 250..5000) { "UDP probe timeout is outside the supported range" }

        val samples = ArrayList<Double?>(attempts)
        repeat(attempts) { index ->
            samples += probeOnce(network, serverAddress, timeoutMs)
            if (index < attempts - 1 && pauseMs > 0) {
                try {
                    Thread.sleep(pauseMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
        return summarize(samples)
    }

    private fun probeOnce(
        network: Network,
        serverAddress: InetAddress,
        timeoutMs: Int
    ): Double? {
        val transactionId = ByteArray(12).also(random::nextBytes)
        val request = StunMessageCodec.createBindingRequest(transactionId)

        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(0))
            // Bind before connect/send; Android requires an unconnected socket here.
            network.bindSocket(socket)
            socket.soTimeout = timeoutMs
            socket.connect(serverAddress, SERVER_PORT)

            val outgoing = DatagramPacket(request, request.size)
            val responseBytes = ByteArray(RESPONSE_BUFFER_BYTES)
            val incoming = DatagramPacket(responseBytes, responseBytes.size)
            val startedAt = SystemClock.elapsedRealtimeNanos()
            socket.send(outgoing)

            return try {
                socket.receive(incoming)
                if (!StunMessageCodec.isMatchingBindingSuccess(
                        incoming.data,
                        incoming.offset,
                        incoming.length,
                        transactionId
                    )
                ) {
                    null
                } else {
                    (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
                }
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }

    private fun summarize(samples: List<Double?>): UdpStunStats {
        val successful = samples.filterNotNull()
        val sorted = successful.sorted()
        val median = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val variation = if (successful.size < 2) null else {
            successful.zipWithNext { first, second -> abs(second - first) }.average()
        }
        return UdpStunStats(
            attempts = samples.size,
            successful = successful.size,
            minMs = sorted.firstOrNull(),
            medianMs = median,
            maxMs = sorted.lastOrNull(),
            variationMs = variation,
            failurePercent = (samples.size - successful.size) * 100.0 / samples.size
        )
    }
}
