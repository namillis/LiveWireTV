package com.livewire.tv.feature.home.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.search.data.WatchedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailsTest {

    private val categories = (1..9).map { ProviderCategory(id = "c$it", name = "Cat $it") }
    private fun ch(id: String, name: String = "Ch $id") = LiveChannel(streamId = id, name = name, categoryId = "c1")
    private fun watched(id: String, name: String = "Ch $id", provider: String = "p1") =
        WatchedChannel(providerId = provider, streamId = id, name = name)

    @Test
    fun `no pins shows the provider's first six categories`() {
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), HomeRails.categoryRails(categories, emptyList()).map { it.id })
    }

    @Test
    fun `pins show in pin order and nothing else`() {
        assertEquals(listOf("c7", "c2"), HomeRails.categoryRails(categories, listOf("c7", "c2")).map { it.id })
    }

    @Test
    fun `pins the provider no longer has are skipped, and if none are left the default returns`() {
        assertEquals(listOf("c3"), HomeRails.categoryRails(categories, listOf("gone", "c3")).map { it.id })
        assertEquals(6, HomeRails.categoryRails(categories, listOf("gone")).size)
    }

    @Test
    fun `toggle pins at the end, unpins, and refuses a seventh`() {
        var r = HomeRails.togglePin(emptyList(), "c1")
        assertEquals(listOf("c1"), r.pinned)
        assertTrue(r.isPinned)
        r = HomeRails.togglePin(listOf("c1", "c2"), "c1")
        assertEquals(listOf("c2"), r.pinned)
        assertFalse(r.isPinned)
        val six = (1..6).map { "c$it" }
        r = HomeRails.togglePin(six, "c7")
        assertEquals(six, r.pinned)
        assertTrue(r.full)
        assertFalse(r.isPinned)
        assertEquals((2..6).map { "c$it" }, HomeRails.togglePin(six, "c1").pinned)
    }

    @Test
    fun `recently watched resolves by id, then by name, for this provider only`() {
        val channels = listOf(ch("1"), ch("2", "ESPN"), ch("3"))
        val history = listOf(
            watched("3"),
            watched("old-espn", "ESPN"),
            watched("1", provider = "other"),
            watched("gone"),
            watched("1"),
            watched("2"),
        )
        assertEquals(listOf("3", "2", "1"), HomeRails.recentlyWatched(history, "p1", channels).map { it.streamId })
    }

    @Test
    fun `focus follows a card when rails above it come and go`() {
        val memory = HomeRails.focusMemory("c2", "7")
        val before = listOf("c1" to listOf("1", "2"), "c2" to listOf("6", "7"))
        assertEquals("1:1", HomeRails.focusPosition(before, memory))
        val after = listOf("\u0000recent" to listOf("9"), "c1" to listOf("1", "2"), "c2" to listOf("6", "7"))
        assertEquals("2:1", HomeRails.focusPosition(after, memory))
    }

    @Test
    fun `focus falls back to the channel on another rail, then to the first card`() {
        val rails = listOf("\u0000favorites" to emptyList(), "c1" to listOf("1", "7"))
        assertEquals("1:1", HomeRails.focusPosition(rails, HomeRails.focusMemory("gone", "7")))
        assertEquals("1:0", HomeRails.focusPosition(rails, HomeRails.focusMemory("c1", "gone")))
        assertEquals("1:0", HomeRails.focusPosition(rails, null))
        assertEquals("1:0", HomeRails.focusPosition(rails, "0:3"))
        assertNull(HomeRails.focusPosition(listOf("c1" to emptyList()), null))
    }
}
