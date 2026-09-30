package com.livewire.tv.feature.epg.data

/**
 * Collapses repeated equal strings to a single shared instance while a guide is being
 * built from XML or from the on-disk cache.
 *
 * A provider guide repeats the same strings tens of thousands of times: one channel id is
 * shared by every programme on that channel (measured: 3.5k distinct ids across 74k
 * programmes), a series' synopsis recurs on every episode (41k duplicate description
 * instances over 32k distinct strings), and titles repeat across airings. Without
 * interning, each XML tag or each decoded JSON element allocates a fresh [String] holding
 * an identical char array, so the retained guide carries many copies of the same text.
 * Routing every string through [intern] keeps one instance per distinct value, which on
 * the measured guide removes ~18 MB of retained heap.
 *
 * Not thread-safe and deliberately short-lived: build one per parse/decode and discard it
 * with the intermediate state. It is NOT [String.intern] (that pins into the JVM string
 * pool for the process lifetime); this table is garbage-collected with the guide.
 */
internal class StringInterner {
    private val pool = HashMap<String, String>()

    /** The canonical instance for [value], or null when [value] is null. */
    fun intern(value: String?): String? {
        if (value == null) return null
        return pool.getOrPut(value) { value }
    }

    /** Non-null convenience for required fields. */
    fun internNonNull(value: String): String = pool.getOrPut(value) { value }
}
