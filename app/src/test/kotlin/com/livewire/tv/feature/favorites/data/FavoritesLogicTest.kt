package com.livewire.tv.feature.favorites.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure list and resolver behaviour for favourites: add/remove/toggle, reordering, ordering
 * stability, and the stream-id-then-name re-match that recovers an M3U favourite whose id
 * changed on a playlist refresh. No Android; every case runs on the JVM.
 */
class FavoritesLogicTest {

    private fun entry(id: String, name: String = id, pos: Int = 0, added: Long = 0L) =
        FavoriteEntry(streamId = id, name = name, addedAt = added, position = pos)

    private fun channel(id: String, name: String = id) =
        LiveChannel(streamId = id, name = name, categoryId = "c")

    private fun ids(entries: List<FavoriteEntry>) = entries.map { it.streamId }

    private fun assertDensePositions(entries: List<FavoriteEntry>) {
        assertEquals((entries.indices).toList(), entries.map { it.position })
    }

    // ── add ──

    @Test
    fun `add appends to the end in add order`() {
        var list = FavoritesLogic.add(emptyList(), entry("a"))
        list = FavoritesLogic.add(list, entry("b"))
        list = FavoritesLogic.add(list, entry("c"))
        assertEquals(listOf("a", "b", "c"), ids(FavoritesLogic.ordered(list)))
        assertDensePositions(FavoritesLogic.ordered(list))
    }

    @Test
    fun `add is idempotent by stream id and refreshes display fields, keeping place`() {
        var list = FavoritesLogic.add(emptyList(), entry("a", name = "A"))
        list = FavoritesLogic.add(list, entry("b", name = "B"))
        list = FavoritesLogic.add(list, entry("a", name = "A HD").copy(epgChannelId = "epg-a"))
        val ordered = FavoritesLogic.ordered(list)
        assertEquals(listOf("a", "b"), ids(ordered))
        assertEquals("A HD", ordered.first { it.streamId == "a" }.name)
        assertEquals("epg-a", ordered.first { it.streamId == "a" }.epgChannelId)
    }

    // ── remove ──

    @Test
    fun `remove drops the entry and closes the position gap`() {
        var list = FavoritesLogic.add(emptyList(), entry("a"))
        list = FavoritesLogic.add(list, entry("b"))
        list = FavoritesLogic.add(list, entry("c"))
        list = FavoritesLogic.remove(list, "b")
        val ordered = FavoritesLogic.ordered(list)
        assertEquals(listOf("a", "c"), ids(ordered))
        assertDensePositions(ordered)
    }

    @Test
    fun `remove of an absent id is a no-op`() {
        val list = FavoritesLogic.add(emptyList(), entry("a"))
        assertEquals(list, FavoritesLogic.remove(list, "zzz"))
    }

    // ── toggle ──

    @Test
    fun `toggle adds when absent and removes when present`() {
        var list = FavoritesLogic.toggle(emptyList(), "a", entry("a"))
        assertEquals(listOf("a"), ids(FavoritesLogic.ordered(list)))
        list = FavoritesLogic.toggle(list, "a", entry("a"))
        assertTrue(list.isEmpty())
    }

    // ── reorder ──

    private fun threeList(): List<FavoriteEntry> {
        var list = FavoritesLogic.add(emptyList(), entry("a"))
        list = FavoritesLogic.add(list, entry("b"))
        return FavoritesLogic.add(list, entry("c"))
    }

    @Test
    fun `moveUp swaps toward the front and is a no-op on the first`() {
        val moved = FavoritesLogic.moveUp(threeList(), "c")
        assertEquals(listOf("a", "c", "b"), ids(FavoritesLogic.ordered(moved)))
        assertEquals(
            listOf("a", "c", "b"),
            ids(FavoritesLogic.ordered(FavoritesLogic.moveUp(moved, "a"))),
        )
        assertDensePositions(FavoritesLogic.ordered(moved))
    }

    @Test
    fun `moveDown swaps toward the end and is a no-op on the last`() {
        val moved = FavoritesLogic.moveDown(threeList(), "a")
        assertEquals(listOf("b", "a", "c"), ids(FavoritesLogic.ordered(moved)))
        assertEquals(
            listOf("b", "a", "c"),
            ids(FavoritesLogic.ordered(FavoritesLogic.moveDown(moved, "c"))),
        )
    }

    @Test
    fun `moveTo places at the target index and clamps out-of-range targets`() {
        assertEquals(
            listOf("b", "c", "a"),
            ids(FavoritesLogic.ordered(FavoritesLogic.moveTo(threeList(), "a", 2))),
        )
        // Beyond the end clamps to last.
        assertEquals(
            listOf("b", "c", "a"),
            ids(FavoritesLogic.ordered(FavoritesLogic.moveTo(threeList(), "a", 99))),
        )
        // Negative clamps to first (a is already first, so unchanged).
        assertEquals(
            listOf("a", "b", "c"),
            ids(FavoritesLogic.ordered(FavoritesLogic.moveTo(threeList(), "a", -5))),
        )
    }

    // ── ordering stability ──

    @Test
    fun `ordered is stable across equal positions using addedAt then id`() {
        val a = entry("a", pos = 0, added = 100)
        val b = entry("b", pos = 0, added = 50)
        val c = entry("c", pos = 0, added = 50)
        // b and c share position and addedAt, so id breaks the tie: b before c. a is later.
        assertEquals(listOf("b", "c", "a"), ids(FavoritesLogic.ordered(listOf(a, b, c))))
    }

    @Test
    fun `ordered follows position even when the input list order is scrambled`() {
        val scrambled = listOf(entry("c", pos = 2), entry("a", pos = 0), entry("b", pos = 1))
        assertEquals(listOf("a", "b", "c"), ids(FavoritesLogic.ordered(scrambled)))
    }

    // ── resolve: stream-id match ──

    @Test
    fun `resolve matches by stream id and preserves stored order`() {
        val entries = threeList()
        val channels = listOf(channel("c"), channel("a"), channel("b")) // provider order differs
        val resolved = FavoritesLogic.resolve(entries, channels)
        assertEquals(listOf("a", "b", "c"), resolved.map { it.entry.streamId })
        assertTrue(resolved.all { !it.unavailable && it.channel != null })
    }

    // ── resolve: name fallback after a streamId change (M3U refresh) ──

    @Test
    fun `resolve falls back to exact name and rewrites the stored streamId`() {
        val stored = FavoritesLogic.add(emptyList(), entry("old-hash", name = "CNN"))
        // Same channel, new URL -> new hash id, same name.
        val channels = listOf(channel("new-hash", name = "CNN"))
        val resolved = FavoritesLogic.resolve(stored, channels)
        assertEquals(1, resolved.size)
        assertFalse(resolved.first().unavailable)
        assertEquals("new-hash", resolved.first().entry.streamId) // rewritten
        assertEquals("new-hash", resolved.first().channel?.streamId)
    }

    @Test
    fun `resolve marks a favourite Unavailable when neither id nor name matches`() {
        val stored = FavoritesLogic.add(emptyList(), entry("gone", name = "Gone TV"))
        val resolved = FavoritesLogic.resolve(stored, listOf(channel("x", name = "Other")))
        assertEquals(1, resolved.size)
        assertTrue(resolved.first().unavailable)
        assertNull(resolved.first().channel)
    }

    @Test
    fun `resolve never claims one live channel for two favourites`() {
        // Two favourites, one lost its id; only one live channel named "Dup" exists.
        var stored = FavoritesLogic.add(emptyList(), entry("dup-id", name = "Dup"))
        stored = FavoritesLogic.add(stored, entry("other-old", name = "Dup"))
        val channels = listOf(channel("dup-id", name = "Dup"))
        val resolved = FavoritesLogic.resolve(stored, channels)
        // First keeps the channel by id; second cannot re-use it by name -> Unavailable.
        assertFalse(resolved[0].unavailable)
        assertTrue(resolved[1].unavailable)
    }
}
