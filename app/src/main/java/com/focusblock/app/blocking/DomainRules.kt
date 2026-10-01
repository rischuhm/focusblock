package com.focusblock.app.blocking

/**
 * Pure-JVM domain handling for the block list. No Android dependencies so it
 * can be unit tested directly.
 */
object DomainRules {

    /**
     * Normalizes user input (which may be a URL, may contain scheme/path/port,
     * uppercase letters, a leading "www." etc.) into a bare lowercase domain.
     * Returns null when the input cannot be a valid domain.
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null

        s = s.removePrefix("http://").removePrefix("https://").removePrefix("//")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("*.")
        s = s.removePrefix("www.")
        s = s.trim('.')

        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return null

        val labels = s.split('.')
        if (labels.size < 2) return null                       // require at least domain.tld
        if (labels.any { it.isEmpty() }) return null
        if (labels.any { label -> !label.all { it.isLetterOrDigit() || it == '-' } }) return null
        if (labels.last().length < 2) return null              // TLD at least 2 chars
        return s
    }
}

/**
 * Matches DNS query names against the block list. Blocking "reddit.com" also
 * blocks every subdomain ("www.reddit.com", "old.reddit.com", ...) but never
 * unrelated domains that merely contain the string ("notreddit.com").
 */
class DomainMatcher(entries: Collection<String>) {

    private val blocked = entries
        .map { it.lowercase().trim('.').removePrefix("www.") }
        .filter { it.isNotEmpty() }
        .toSet()

    val size: Int get() = blocked.size

    fun isBlocked(queryName: String): Boolean {
        val q = queryName.lowercase().trim('.').removePrefix("www.")
        if (q in blocked) return true
        // Walk up the label hierarchy: a.b.reddit.com -> reddit.com must match.
        var idx = q.indexOf('.')
        while (idx >= 0 && idx + 1 < q.length) {
            if (q.substring(idx + 1) in blocked) return true
            idx = q.indexOf('.', idx + 1)
        }
        return false
    }
}
