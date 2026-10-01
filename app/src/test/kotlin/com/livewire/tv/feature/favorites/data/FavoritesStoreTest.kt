package com.livewire.tv.feature.favorites.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file-backed store: per-provider isolation, reactive Flows, provider-delete cleanup, and
 * a persistence round-trip through a fresh instance. Driven against a real temp directory so
 * the on-disk path is exercised without an Android Context (the pattern EpgCacheTest uses).
 */
class FavoritesStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FavoritesStore(tmp.newFolder())

    private fun channel(id: String, name: String = id) =
        LiveChannel(streamId = id, name = name, categoryId = "c")

    private suspend fun idsOf(store: FavoritesStore, provider: String) =
        store.favorites(provider).first().map { it.streamId }

    @Test
    fun `add then favorites emits the entry, ordered`() = runTest {
        val store = store()
        store.add("p1", channel("a"), now = 1)
        store.add("p1", channel("b"), now = 2)
        assertEquals(listOf("a", "b"), idsOf(store, "p1"))
    }

    @Test
    fun `favoriteIds and isFavorite reflect the current set`() = runTest {
        val store = store()
        store.add("p1", channel("a"))
        assertEquals(setOf("a"), store.favoriteIds("p1").first())
        assertTrue(store.isFavorite("p1", "a"))
        assertFalse(store.isFavorite("p1", "b"))
    }

    @Test
    fun `toggle adds then removes`() = runTest {
        val store = store()
        store.toggle("p1", channel("a"))
        assertEquals(listOf("a"), idsOf(store, "p1"))
        store.toggle("p1", channel("a"))
        assertTrue(idsOf(store, "p1").isEmpty())
    }

    @Test
    fun `remove drops one favourite`() = runTest {
        val store = store()
        store.add("p1", channel("a"))
        store.add("p1", channel("b"))
        store.remove("p1", "a")
        assertEquals(listOf("b"), idsOf(store, "p1"))
    }

    @Test
    fun `reorder via moveUp persists the new order`() = runTest {
        val store = store()
        store.add("p1", channel("a"), now = 1)
        store.add("p1", channel("b"), now = 2)
        store.add("p1", channel("c"), now = 3)
        store.moveUp("p1", "c")
        assertEquals(listOf("a", "c", "b"), idsOf(store, "p1"))
    }

    @Test
    fun `favourites are isolated per provider`() = runTest {
        val store = store()
        store.add("p1", channel("a"))
        store.add("p2", channel("b"))
        assertEquals(listOf("a"), idsOf(store, "p1"))
        assertEquals(listOf("b"), idsOf(store, "p2"))
    }

    @Test
    fun `removeProvider clears only that provider`() = runTest {
        val store = store()
        store.add("p1", channel("a"))
        store.add("p2", channel("b"))
        store.removeProvider("p1")
        assertTrue(idsOf(store, "p1").isEmpty())
        assertEquals(listOf("b"), idsOf(store, "p2"))
    }

    @Test
    fun `entries survive a round-trip through a fresh instance over the same directory`() = runTest {
        val dir = tmp.newFolder()
        FavoritesStore(dir).apply {
            add("p1", channel("a", name = "A"), now = 1)
            add("p1", channel("b", name = "B"), now = 2)
            moveUp("p1", "b") // order becomes b, a
        }
        // A brand-new instance reads the persisted files from scratch.
        val reopened = FavoritesStore(dir)
        val entries = reopened.favorites("p1").first()
        assertEquals(listOf("b", "a"), entries.map { it.streamId })
        assertEquals(listOf(0, 1), entries.map { it.position })
        assertEquals("B", entries.first().name)
    }

    @Test
    fun `removeProvider persists so a fresh instance sees no favourites`() = runTest {
        val dir = tmp.newFolder()
        FavoritesStore(dir).apply {
            add("p1", channel("a"))
            removeProvider("p1")
        }
        assertTrue(FavoritesStore(dir).favorites("p1").first().isEmpty())
    }

    @Test
    fun `applyRematches persists a renamed M3U id in place and keeps order`() = runTest {
        val dir = tmp.newFolder()
        val store = FavoritesStore(dir)
        store.add("p1", channel("old", name = "US - FOX HD"), now = 1)
        store.add("p1", channel("b", name = "US - CNN"), now = 2)
        // Playlist refresh: FOX's URL rotated, so its hashed id changed.
        val live = listOf(channel("new", name = "US - FOX HD"), channel("b", name = "US - CNN"))
        val resolved = FavoritesLogic.resolve(store.favorites("p1").first(), live)
        store.applyRematches("p1", resolved)

        assertEquals(listOf("new", "b"), idsOf(store, "p1"))
        assertEquals(listOf("new", "b"), idsOf(FavoritesStore(dir), "p1"))
        assertTrue(store.isFavorite("p1", "new"))
        assertFalse(store.isFavorite("p1", "old"))
    }

    @Test
    fun `applyRematches ignores a stale resolve after the list changed`() = runTest {
        val store = store()
        store.add("p1", channel("old", name = "FOX"), now = 1)
        val resolved = FavoritesLogic.resolve(store.favorites("p1").first(), listOf(channel("new", name = "FOX")))
        store.remove("p1", "old")
        store.add("p1", channel("x", name = "CNN"), now = 2)
        store.applyRematches("p1", resolved)
        assertEquals(listOf("x"), idsOf(store, "p1"))
    }
}
