package com.focusblock.app

import com.focusblock.app.net.DnsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessagesTest {

    /** Builds a real DNS query for [name], type A, with the given ID. */
    private fun buildQuery(id: Int, name: String): ByteArray {
        val labels = name.split(".")
        val qnameLen = labels.sumOf { it.length + 1 } + 1
        val msg = ByteArray(12 + qnameLen + 4)
        msg[0] = (id ushr 8).toByte()
        msg[1] = (id and 0xff).toByte()
        msg[2] = 0x01 // RD
        msg[3] = 0
        msg[4] = 0; msg[5] = 1 // QDCOUNT=1
        var off = 12
        for (label in labels) {
            msg[off++] = label.length.toByte()
            for (c in label) msg[off++] = c.code.toByte()
        }
        msg[off++] = 0
        msg[off++] = 0; msg[off++] = 1 // QTYPE=A
        msg[off++] = 0; msg[off++] = 1 // QCLASS=IN
        return msg
    }

    @Test
    fun extractsQueryName() {
        val q = buildQuery(0x1234, "www.example.com")
        assertEquals("www.example.com", DnsMessages.extractQueryName(q, q.size))
    }

    @Test
    fun extractsSingleLabelName() {
        val q = buildQuery(1, "localhost")
        assertEquals("localhost", DnsMessages.extractQueryName(q, q.size))
    }

    @Test
    fun rejectsShortAndMalformedMessages() {
        assertNull(DnsMessages.extractQueryName(ByteArray(5), 5))
        val noQuestion = ByteArray(12)
        assertNull(DnsMessages.extractQueryName(noQuestion, 12))
        val truncated = buildQuery(1, "www.example.com").copyOfRange(0, 15)
        assertNull(DnsMessages.extractQueryName(truncated, truncated.size))
    }

    @Test
    fun buildsValidNxdomainResponse() {
        val q = buildQuery(0xBEEF, "reddit.com")
        val resp = DnsMessages.buildNxdomainResponse(q, q.size)
        assertNotNull(resp)
        resp!!
        // ID preserved
        assertEquals((0xBEEF ushr 8).toByte(), resp[0])
        assertEquals((0xBEEF and 0xff).toByte(), resp[1])
        // QR=1
        assertTrue(resp[2].toInt() and 0x80 != 0)
        // RD copied
        assertEquals(q[2].toInt() and 0x01, resp[2].toInt() and 0x01)
        // RCODE=3
        assertEquals(3, resp[3].toInt() and 0x0f)
        // QDCOUNT=1, others 0
        assertEquals(0, resp[4].toInt()); assertEquals(1, resp[5].toInt())
        for (i in 6..11) assertEquals(0, resp[i].toInt())
        // Question section copied verbatim, nothing else appended
        assertEquals(q.size, resp.size)
        for (i in 12 until q.size) assertEquals(q[i], resp[i])
    }

    @Test
    fun responseToTruncatedQueryIsNull() {
        val q = buildQuery(1, "www.example.com").copyOfRange(0, 14)
        assertNull(DnsMessages.buildNxdomainResponse(q, q.size))
    }
}
