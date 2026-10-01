package com.focusblock.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Relays DNS payloads between one app-side client (srcIp:srcPort) and one
 * upstream resolver through a VPN-protected socket. Replies are re-wrapped
 * into IPv4/UDP packets addressed back to the original client and handed to
 * [onPacket] (which writes them into the TUN device).
 *
 * The forwarder owns its socket; it self-terminates after [idleTimeoutMs] of
 * inactivity and calls [onClose] so the owner can drop it from its map.
 */
class UdpForwarder(
    private val socket: DatagramSocket,
    private val clientIp: Int,
    private val clientPort: Int,
    private val serverIp: Int,
    private val serverPort: Int,
    private val onPacket: (ByteArray) -> Unit,
    private val onClose: (UdpForwarder) -> Unit,
    private val idleTimeoutMs: Long = 180_000L,
) {
    private val serverAddress: InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            (serverIp ushr 24).toByte(), (serverIp ushr 16).toByte(),
            (serverIp ushr 8).toByte(), serverIp.toByte(),
        )
    )

    @Volatile private var lastActivityMs = System.currentTimeMillis()
    @Volatile private var closed = false

    private val reader = thread(name = "focusblock-fwd-$clientPort", isDaemon = true) {
        val buf = ByteArray(4096)
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.soTimeout = 5_000
                socket.receive(packet)
            } catch (e: Exception) {
                if (closed) break
                continue // timeout; loop checks closed flag
            }
            lastActivityMs = System.currentTimeMillis()
            val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val out = Ipv4Udp.buildPacket(serverIp, serverPort, clientIp, clientPort, payload)
            try {
                onPacket(out)
            } catch (e: Exception) {
                break
            }
        }
        close()
    }

    private val watchdog = thread(name = "focusblock-fwd-watch", isDaemon = true) {
        while (!closed) {
            Thread.sleep(10_000)
            if (!closed && System.currentTimeMillis() - lastActivityMs > idleTimeoutMs) {
                close()
                break
            }
        }
    }

    /** Sends one client payload to the upstream resolver. Throws if closed. */
    fun send(payload: ByteArray) {
        if (closed) throw IllegalStateException("forwarder is closed")
        lastActivityMs = System.currentTimeMillis()
        socket.send(DatagramPacket(payload, payload.size, serverAddress, serverPort))
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
        onClose(this)
    }

    fun isClosed(): Boolean = closed
}
