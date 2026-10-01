package com.focusblock.app

import com.focusblock.app.net.Ipv4Udp
import com.focusblock.app.net.UdpForwarder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration test of the DNS forwarding path on the JVM: a UDP echo server
 * plays the upstream resolver, the forwarder relays a query and the reply is
 * re-wrapped into a correct IPv4/UDP packet addressed to the original client.
 *
 * Regression test for the "no website loads" bug: the injected reply must
 * claim the address the client originally QUERIED (the tunnel's dummy DNS
 * 10.0.0.2), not the real upstream's address — UDP replies from an unexpected
 * source are dropped by the client's kernel.
 */
class UdpForwarderTest {

    @Test
    fun forwardsPayloadAndRepliesFromQueriedAddress() {
        // Upstream echo server on 127.0.0.1 with an ephemeral port.
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val serverPort = server.localPort
        val serverDone = CountDownLatch(1)
        val serverThread = thread(isDaemon = true) {
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            server.receive(pkt)               // got the query
            server.send(pkt)                  // echo it straight back
            serverDone.countDown()
        }

        val latch = CountDownLatch(1)
        var received: ByteArray? = null
        val clientIp = Ipv4Udp.ipToInt("10.0.0.1")
        val clientPort = 40000
        val serverIp = Ipv4Udp.ipToInt("127.0.0.1")
        val dummyDnsIp = Ipv4Udp.ipToInt("10.0.0.2") // what the client "queried"

        val fwd = UdpForwarder(
            socket = DatagramSocket(),
            clientIp = clientIp,
            clientPort = clientPort,
            serverIp = serverIp,
            serverPort = serverPort,
            replySourceIp = dummyDnsIp,
            replySourcePort = 53,
            onPacket = { packet ->
                received = packet
                latch.countDown()
            },
            onClose = {},
            idleTimeoutMs = 60_000,
        )

        val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(), 0, 0, 1, 0, 1)
        fwd.send(query)

        assertTrue("timed out waiting for upstream reply", latch.await(5, TimeUnit.SECONDS))
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))

        val packet = received!!
        val parsed = Ipv4Udp.parse(packet, packet.size)!!
        // Regression assertions: reply must come from the QUERIED dummy DNS...
        assertEquals(dummyDnsIp, parsed.srcIp)
        assertEquals(53, parsed.srcPort)
        // ...and be addressed to the original client.
        assertEquals(clientIp, parsed.dstIp)
        assertEquals(clientPort, parsed.dstPort)
        val payload = packet.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength)
        assertArrayEquals(query, payload)

        fwd.close()
        server.close()
        serverThread.join(2000)
    }
}
