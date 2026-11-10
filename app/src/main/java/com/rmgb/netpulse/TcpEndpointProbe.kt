package com.rmgb.netpulse

import android.net.Network
import android.os.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures TCP connection-establishment time while pinning each socket to a
 * specific Android Network. Results describe endpoint reachability only.
 */
object TcpEndpointProbe {
    fun measure(
        network: Network,
        address: InetAddress,
        port: Int,
        attempts: Int = 5,
        timeoutMs: Int = 1800,
        pauseMs: Long = 100L
    ): TcpProbeStats {
        require(port in 1..65535) { "TCP port must be between 1 and 65535" }
        require(attempts in 1..20) { "TCP attempts must be between 1 and 20" }
        require(timeoutMs in 250..10000) { "TCP timeout is outside the supported range" }
        val samples = ArrayList<Long?>(attempts)
        repeat(attempts) { index ->
            val elapsed = runCatching {
                val started = SystemClock.elapsedRealtime()
                Socket().use { socket ->
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                }
                SystemClock.elapsedRealtime() - started
            }.getOrNull()
            samples += elapsed
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
