package com.livewire.tv.feature.search.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentHistoryLogicTest {

    // ── addSearch: minimum length ──

    @Test
    fun `rejects a query shorter than the minimum`() {
        assertEquals(emptyList<String>(), RecentHistoryLogic.addSearch(emptyList(), "f"))
    }

    @Test
    fun `rejects a query that is only whitespace`() {
        assertEquals(listOf("fox"), RecentHistoryLogic.addSearch(listOf("fox"), "   "))
    }

    @Test
    fun `trims a query before measuring its length`() {
        assertEquals(listOf("fo"), RecentHistoryLogic.addSearch(emptyList(), "  fo  "))
    }

    @Test
    fun `accepts a query at exactly the minimum length`() {
        assertEquals(listOf("fo"), RecentHistoryLogic.addSearch(emptyList(), "fo"))
    }

    // ── addSearch: ordering + dedupe ──

    @Test
    fun `prepends a new query, most recent first`() {
        val out = RecentHistoryLogic.addSearch(listOf("espn", "cnn"), "fox")
        assertEquals(listOf("fox", "espn", "cnn"), out)
    }

    @Test
    fun `moves a repeated query to the front instead of duplicating`() {
        val out = RecentHistoryLogic.addSearch(listOf("espn", "cnn", "fox"), "cnn")
        assertEquals(listOf("cnn", "espn", "fox"), out)
    }

    @Test
    fun `dedupes case-insensitively and keeps the new casing`() {
        val out = RecentHistoryLogic.addSearch(listOf("fox", "cnn"), "FOX")
        assertEquals(listOf("FOX", "cnn"), out)
    }

    // ── addSearch: cap ──

    @Test
    fun `caps recent searches at the maximum, dropping the oldest`() {
        val full = listOf("a1", "b1", "c1", "d1", "e1", "f1") // MAX_SEARCHES = 6
        val out = RecentHistoryLogic.addSearch(full, "g1")
        assertEquals(RecentHistoryLogic.MAX_SEARCHES, out.size)
        assertEquals("g1", out.first())
        assertEquals(listOf("g1", "a1", "b1", "c1", "d1", "e1"), out)
    }

    // ── addWatched: ordering + dedupe ──

    private fun w(id: String, name: String = id, provider: String = "p1") =
        WatchedChannel(providerId = provider, streamId = id, name = name)

    @Test
    fun `prepends a new watched channel, most recent first`() {
        val out = RecentHistoryLogic.addWatched(listOf(w("cnn")), w("fox"))
        assertEquals(listOf("fox", "cnn"), out.map { it.streamId })
    }

    @Test
    fun `dedupes a watched channel by provider and stream id, newest wins`() {
        val current = listOf(w("cnn"), w("fox"))
        val out = RecentHistoryLogic.addWatched(current, w("cnn", name = "CNN HD"))
        assertEquals(listOf("cnn", "fox"), out.map { it.streamId })
        assertEquals("CNN HD", out.first().name)
    }

    @Test
    fun `same stream id on a different provider is not a duplicate`() {
        val current = listOf(w("100", provider = "p1"))
        val out = RecentHistoryLogic.addWatched(current, w("100", provider = "p2"))
        assertEquals(2, out.size)
        assertEquals(listOf("p2", "p1"), out.map { it.providerId })
    }

    // ── addWatched: cap ──

    @Test
    fun `caps watched channels at the maximum, dropping the oldest`() {
        val full = (1..RecentHistoryLogic.MAX_WATCHED).map { w("s$it") } // 8
        val out = RecentHistoryLogic.addWatched(full, w("new"))
        assertEquals(RecentHistoryLogic.MAX_WATCHED, out.size)
        assertEquals("new", out.first().streamId)
        // The oldest (last) entry fell off.
        assertEquals("s${RecentHistoryLogic.MAX_WATCHED - 1}", out.last().streamId)
    }
}
