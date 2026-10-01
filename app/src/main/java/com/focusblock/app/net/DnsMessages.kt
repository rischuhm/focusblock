package com.focusblock.app.net

/**
 * Minimal DNS message helpers. Only what a blocking resolver needs:
 * extracting the queried name from a request and forging an NXDOMAIN
 * (domain does not exist) reply. Pure JVM, unit tested.
 */
object DnsMessages {

    /**
     * Extracts the first question's domain name (e.g. "www.example.com")
     * from a DNS message, or null if the message is malformed.
     */
    fun extractQueryName(message: ByteArray, length: Int): String? {
        if (length < 12) return null
        val qdCount = ((message[4].toInt() and 0xff) shl 8) or (message[5].toInt() and 0xff)
        if (qdCount < 1) return null

        var offset = 12
        var jumps = 0
        val name = StringBuilder()
        while (offset < length && jumps < 8) {
            val len = message[offset].toInt() and 0xff
            when {
                len == 0 -> return name.toString()
                len and 0xc0 == 0xc0 -> {
                    // Compression pointer (not expected in a question, but tolerate it).
                    if (offset + 1 >= length) return null
                    offset = ((len and 0x3f) shl 8) or (message[offset + 1].toInt() and 0xff)
                    jumps++
                }
                len and 0xc0 != 0 -> return null
                offset + 1 + len > length -> return null
                else -> {
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(message, offset + 1, len, Charsets.US_ASCII))
                    offset += 1 + len
                }
            }
        }
        return null
    }

    /**
     * Builds an NXDOMAIN (RCODE=3) response for the given DNS query,
     * preserving the query ID, the RD flag and the question section.
     */
    fun buildNxdomainResponse(query: ByteArray, length: Int): ByteArray? {
        val questionEnd = findQuestionEnd(query, length) ?: return null
        val out = ByteArray(12 + (questionEnd - 12))
        // ID
        out[0] = query[0]
        out[1] = query[1]
        // Flags: QR=1, opcode 0, RD copied from query, RCODE=3 (NXDOMAIN)
        val rd = query[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte()
        out[3] = 0x03
        // QDCOUNT=1, ANCOUNT=NSCOUNT=ARCOUNT=0
        out[4] = 0
        out[5] = 1
        for (i in 6..11) out[i] = 0
        System.arraycopy(query, 12, out, 12, questionEnd - 12)
        return out
    }

    /** Returns the offset just past the first question (QNAME + QTYPE + QCLASS). */
    private fun findQuestionEnd(msg: ByteArray, length: Int): Int? {
        if (length < 12) return null
        var offset = 12
        var jumps = 0
        while (offset < length && jumps < 8) {
            val len = msg[offset].toInt() and 0xff
            when {
                len == 0 -> {
                    offset += 1
                    break
                }
                len and 0xc0 == 0xc0 -> {
                    offset += 2
                    break
                }
                len and 0xc0 != 0 -> return null
                else -> offset += 1 + len
            }
        }
        if (offset + 4 > length) return null
        return offset + 4
    }
}
