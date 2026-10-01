package com.focusblock.app

import android.content.Context
import android.content.SharedPreferences

/** Simple SharedPreferences-backed store for the blocked domain list. */
class BlockListStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<String> =
        prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty().sorted()

    fun add(domain: String) {
        val set = prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty().toMutableSet()
        set.add(domain)
        prefs.edit().putStringSet(KEY_DOMAINS, set).apply()
    }

    fun remove(domain: String) {
        val set = prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty().toMutableSet()
        set.remove(domain)
        prefs.edit().putStringSet(KEY_DOMAINS, set).apply()
    }

    companion object {
        private const val PREFS_NAME = "focusblock_blocklist"
        private const val KEY_DOMAINS = "domains"
    }
}
