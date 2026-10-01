package com.focusblock.app.net

/**
 * Minimal IPv4/UDP packet parsing and construction for the VPN tunnel.
 * Pure JVM, unit tested. Only unfragmented IPv4 packets with UDP are handled.
 */
object Ipv4Udp {

    const val PROTO_UDP = 17
    const val UDP_HEADER_LEN = 8
    const val IP_HEADER_LEN = 20

    /** Parsed view of an IPv4 packet carrying UDP. */
    class UdpInfo(
        val srcIp: Int,
        val dstIp: Int,
        val srcPort: Int,
        val dstPort: Int,
        val ipHeaderLength: Int,
        val totalLength: Int,
    ) {
        val payloadOffset: Int get() = ipHeaderLength + UDP_HEADER_LEN
        val payloadLength: Int get() = totalLength - payloadOffset
    }

    fun parse(packet: ByteArray, length: Int): UdpInfo? {
        if (length < IP_HEADER_LEN + UDP_HEADER_LEN) return null
        if (packet[0].toInt() ushr 4 != 4) return null              // IPv4 only
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < IP_HEADER_LEN) return null

        val totalLength = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (totalLength < ipHeaderLength + UDP_HEADER_LEN || totalLength > length) return null

        // Drop fragments (MF flag or non-zero fragment offset).
        val flagsAndOffset = ((packet[6].toInt() and 0xff) shl 8) or (packet[7].toInt() and 0xff)
        if (flagsAndOffset and 0x3fff != 0) return null

        if (packet[9].toInt() and 0xff != PROTO_UDP) return null

        val udpLength = ((packet[ipHeaderLength + 4].toInt() and 0xff) shl 8) or
            (packet[ipHeaderLength + 5].toInt() and 0xff)
        if (udpLength < UDP_HEADER_LEN || ipHeaderLength + udpLength > totalLength) return null

        return UdpInfo(
            srcIp = readIp(packet, 12),
            dstIp = readIp(packet, 16),
            srcPort = readShort(packet, ipHeaderLength),
            dstPort = readShort(packet, ipHeaderLength + 2),
            ipHeaderLength = ipHeaderLength,
            totalLength = ipHeaderLength + udpLength,
        )
    }

    /**
     * Builds a complete IPv4+UDP packet. The UDP checksum is left at 0, which
     * is legal for IPv4. The IP header checksum is computed.
     */
    fun buildPacket(
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = IP_HEADER_LEN + UDP_HEADER_LEN + payload.size
        val out = ByteArray(totalLength)
        out[0] = 0x45                          // version 4, IHL 5
        out[1] = 0                             // TOS
        writeShort(out, 2, totalLength)
        writeShort(out, 4, 0x4242)             // ID
        writeShort(out, 6, 0)                  // flags / fragment offset
        out[8] = 64                            // TTL
        out[9] = PROTO_UDP.toByte()
        writeShort(out, 10, 0)                 // checksum placeholder
        writeIp(out, 12, srcIp)
        writeIp(out, 16, dstIp)
        writeShort(out, 20, srcPort)
        writeShort(out, 22, dstPort)
        writeShort(out, 24, UDP_HEADER_LEN + payload.size)
        writeShort(out, 26, 0)                 // UDP checksum (0 = disabled for IPv4)
        System.arraycopy(payload, 0, out, 28, payload.size)
        writeShort(out, 10, ipHeaderChecksum(out, 0, IP_HEADER_LEN))
        return out
    }

    /** Convenience: builds the UDP reply corresponding to a parsed request. */
    fun buildResponse(request: UdpInfo, payload: ByteArray): ByteArray =
        buildPacket(request.dstIp, request.dstPort, request.srcIp, request.srcPort, payload)

    fun ipHeaderChecksum(header: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    fun readIp(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 24) or
            ((packet[offset + 1].toInt() and 0xff) shl 16) or
            ((packet[offset + 2].toInt() and 0xff) shl 8) or
            (packet[offset + 3].toInt() and 0xff)

    fun writeIp(packet: ByteArray, offset: Int, ip: Int) {
        packet[offset] = (ip ushr 24).toByte()
        packet[offset + 1] = (ip ushr 16).toByte()
        packet[offset + 2] = (ip ushr 8).toByte()
        packet[offset + 3] = ip.toByte()
    }

    fun readShort(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    fun writeShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value ushr 8) and 0xff).toByte()
        packet[offset + 1] = (value and 0xff).toByte()
    }

    fun ipToInt(dotted: String): Int {
        val parts = dotted.split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $dotted" }
        var ip = 0
        for (p in parts) ip = (ip shl 8) or (p.toInt() and 0xff)
        return ip
    }

    fun intToIp(ip: Int): String =
        "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 8) and 0xff}.${ip and 0xff}"
}
