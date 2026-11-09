package com.rmgb.netpulse

import android.net.Network
import android.os.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Measures TCP connection-establishment time through a specific Android Network.
 * A socket created by Network.socketFactory is pinned to that network. The same
 * resolved address can therefore be compared on physical and VPN networks.
 *
 * This measures TCP connect success/time only; it is not UDP game ping or packet loss.
 */
object TcpEndpointProbe {
    fun measure(
        network: Network,
        address: InetAddress,
        port: Int,
        attempts: Int = 10,
        timeoutMs: Int = 2000,
        pauseMs: Long = 100L
    ): TcpProbeStats {
        require(port in 1..65535) { "TCP target port must be between 1 and 65535" }
        require(attempts in 3..20) { "Use between 3 and 20 TCP samples" }
        require(timeoutMs in 250..5000) { "TCP connection timeout is outside the supported range" }

        val samples = ArrayList<Long?>(attempts)
        repeat(attempts) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            val result = runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                }
                ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L)
                    .coerceAtLeast(0L)
            }.getOrNull()
            samples += result

            if (index < attempts - 1 && pauseMs > 0L) {
                try {
                    Thread.sleep(pauseMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
        return TcpProbeSummary.from(samples)
    }
}
