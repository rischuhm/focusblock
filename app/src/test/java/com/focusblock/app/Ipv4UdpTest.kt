package com.focusblock.app

import com.focusblock.app.net.Ipv4Udp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4UdpTest {

    private val dnsQuery = byteArrayOf(
        0x12.toByte(), 0x34.toByte(), 0x01.toByte(), 0x00.toByte(), // ID, RD
        0, 1, 0, 0, 0, 0, 0, 0,                                        // QDCOUNT=1
        3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0, 0, 1, 0, 1,
    )

    private fun buildRequestPacket(): ByteArray {
        val payload = dnsQuery
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        Ipv4Udp.writeShort(p, 2, total)
        p[8] = 64
        p[9] = 17
        Ipv4Udp.writeIp(p, 12, Ipv4Udp.ipToInt("10.0.0.1"))
        Ipv4Udp.writeIp(p, 16, Ipv4Udp.ipToInt("10.0.0.2"))
        Ipv4Udp.writeShort(p, 20, 40000)
        Ipv4Udp.writeShort(p, 22, 53)
        Ipv4Udp.writeShort(p, 24, 8 + payload.size)
        System.arraycopy(payload, 0, p, 28, payload.size)
        Ipv4Udp.writeShort(p, 10, Ipv4Udp.ipHeaderChecksum(p, 0, 20))
        return p
    }

    @Test
    fun parsesUdpPacket() {
        val p = buildRequestPacket()
        val udp = Ipv4Udp.parse(p, p.size)
        assertNotNull(udp)
        udp!!
        assertEquals(Ipv4Udp.ipToInt("10.0.0.1"), udp.srcIp)
        assertEquals(Ipv4Udp.ipToInt("10.0.0.2"), udp.dstIp)
        assertEquals(40000, udp.srcPort)
        assertEquals(53, udp.dstPort)
        assertEquals(dnsQuery.size, udp.payloadLength)
        assertArrayEquals(dnsQuery, p.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength))
    }

    @Test
    fun rejectsNonUdpAndFragments() {
        val p = buildRequestPacket()
        p[9] = 6 // TCP
        assertNull(Ipv4Udp.parse(p, p.size))
        p[9] = 17
        p[6] = 0x20 // MF flag set
        assertNull(Ipv4Udp.parse(p, p.size))
    }

    @Test
    fun buildResponseSwapsAddressesAndPorts() {
        val req = Ipv4Udp.parse(buildRequestPacket(), buildRequestPacket().size)!!
        val respPayload = byteArrayOf(1, 2, 3, 4)
        val resp = Ipv4Udp.buildResponse(req, respPayload)
        val parsed = Ipv4Udp.parse(resp, resp.size)!!
        assertEquals(req.dstIp, parsed.srcIp)
        assertEquals(req.srcIp, parsed.dstIp)
        assertEquals(53, parsed.srcPort)
        assertEquals(40000, parsed.dstPort)
        assertArrayEquals(respPayload, resp.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength))
        // IP header checksum must validate (sum of words including checksum has top bits 0xffff)
        assertEquals(0, Ipv4Udp.ipHeaderChecksum(resp, 0, 20))
    }

    @Test
    fun ipConversionsRoundTrip() {
        assertEquals("10.0.0.2", Ipv4Udp.intToIp(Ipv4Udp.ipToInt("10.0.0.2")))
        assertEquals("208.67.222.222", Ipv4Udp.intToIp(Ipv4Udp.ipToInt("208.67.222.222")))
    }
}
