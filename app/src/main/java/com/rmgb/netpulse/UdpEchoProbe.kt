package com.rmgb.netpulse

import android.net.Network
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Measures round-trip time to a user-provided UDP echo service.
 *
 * This only produces valid RTT samples when the endpoint echoes each datagram
 * byte-for-byte. Most commercial game servers do not implement this diagnostic
 * envelope; no arbitrary proprietary game packets are generated here.
 */
object UdpEchoProbe {
    fun measure(
        network: Network,
        address: InetAddress,
        port: Int,
        userPayload: ByteArray,
        attempts: Int = 5,
        timeoutMs: Int = 1200,
        pauseMs: Long = 100L
    ): UdpStunStats {
        require(port in 1..65535) { "UDP port must be between 1 and 65535" }
        require(attempts in 1..10) { "UDP attempts must be between 1 and 10" }
        require(timeoutMs in 250..5000) { "UDP timeout is outside the supported range" }
        require(userPayload.size <= UdpEchoPacketCodec.MAX_USER_PAYLOAD_BYTES) {
            "UDP diagnostic payload is too large"
        }

        val samples = ArrayList<Double?>(attempts)
        repeat(attempts) { index ->
            samples += probeOnce(network, address, port, userPayload, timeoutMs)
            if (index < attempts - 1 && pauseMs > 0L) {
                try {
                    Thread.sleep(pauseMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
        return UdpStunSummary.from(samples)
    }

    private fun probeOnce(
        network: Network,
        address: InetAddress,
        port: Int,
        userPayload: ByteArray,
        timeoutMs: Int
    ): Double? {
        val request = UdpEchoPacketCodec.buildRequest(userPayload)
        return try {
            DatagramSocket(null).use { socket ->
                socket.bind(InetSocketAddress(0))
                network.bindSocket(socket)
                socket.soTimeout = timeoutMs
                socket.connect(address, port)
                val responseBuffer = ByteArray(request.size + 1)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                val started = SystemClock.elapsedRealtimeNanos()
                socket.send(DatagramPacket(request, request.size))
                socket.receive(response)
                if (!UdpEchoPacketCodec.isMatchingEcho(request, response.data, response.length)) {
                    null
                } else {
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                }
            }
        } catch (_: SocketTimeoutException) {
            null
        }
    }
}
