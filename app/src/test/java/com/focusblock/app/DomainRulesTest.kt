package com.focusblock.app

import com.focusblock.app.blocking.DomainMatcher
import com.focusblock.app.blocking.DomainRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {

    @Test
    fun normalizePlainDomain() {
        assertEquals("reddit.com", DomainRules.normalize("reddit.com"))
    }

    @Test
    fun normalizeFullUrl() {
        assertEquals("reddit.com", DomainRules.normalize("https://www.reddit.com/r/all/top/?t=hour"))
    }

    @Test
    fun normalizeHttpWithPort() {
        assertEquals("example.org", DomainRules.normalize("http://example.org:8080/path"))
    }

    @Test
    fun normalizeUppercaseAndWwwAndWildcard() {
        assertEquals("youtube.com", DomainRules.normalize("HTTPS://WWW.YOUTUBE.COM/watch"))
        assertEquals("facebook.com", DomainRules.normalize("*.facebook.com"))
    }

    @Test
    fun normalizeTrailingDot() {
        assertEquals("reddit.com", DomainRules.normalize("reddit.com."))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(DomainRules.normalize(""))
        assertNull(DomainRules.normalize("   "))
        assertNull(DomainRules.normalize("http://"))
        assertNull(DomainRules.normalize("localhost"))
        assertNull(DomainRules.normalize("not a domain"))
        assertNull(DomainRules.normalize("onlytld."))
        assertNull(DomainRules.normalize("a.b")) // TLD too short -> hmm, "a.b" TLD is "b" length 1 -> rejected
    }

    @Test
    fun matcherBlocksExactAndSubdomains() {
        val m = DomainMatcher(listOf("reddit.com"))
        assertTrue(m.isBlocked("reddit.com"))
        assertTrue(m.isBlocked("www.reddit.com"))
        assertTrue(m.isBlocked("old.reddit.com"))
        assertTrue(m.isBlocked("a.b.c.reddit.com"))
        assertTrue(m.isBlocked("reddit.com.")) // FQDN trailing dot
    }

    @Test
    fun matcherDoesNotBlockLookalikes() {
        val m = DomainMatcher(listOf("reddit.com"))
        assertFalse(m.isBlocked("notreddit.com"))
        assertFalse(m.isBlocked("reddit.com.evil.com"))
        assertFalse(m.isBlocked("google.com"))
    }

    @Test
    fun matcherIsCaseInsensitiveAndHandlesWwwEntries() {
        val m = DomainMatcher(listOf("WWW.Example.COM"))
        assertTrue(m.isBlocked("example.com"))
        assertTrue(m.isBlocked("WWW.EXAMPLE.COM"))
        assertTrue(m.isBlocked("api.example.com"))
    }

    @Test
    fun matcherMultipleEntries() {
        val m = DomainMatcher(listOf("twitter.com", "x.com", "news.ycombinator.com"))
        assertTrue(m.isBlocked("mobile.twitter.com"))
        assertTrue(m.isBlocked("news.ycombinator.com"))
        assertTrue(m.isBlocked("www.news.ycombinator.com"))
        assertFalse(m.isBlocked("ycombinator.com"))
    }

    @Test
    fun matcherEmptyBlocksNothing() {
        val m = DomainMatcher(emptyList())
        assertFalse(m.isBlocked("reddit.com"))
        assertEquals(0, m.size)
    }
}
