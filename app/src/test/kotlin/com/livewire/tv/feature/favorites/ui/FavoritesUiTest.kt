package com.livewire.tv.feature.favorites.ui

import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure favorites UI helpers: the entry builder and the menu/toast labels. */
class FavoritesUiTest {

    private fun channel(id: String, name: String = id, epg: String? = null) =
        LiveChannel(streamId = id, name = name, epgChannelId = epg, categoryId = "c")

    @Test
    fun `entryFor copies id name epg and stamps the add time`() {
        val entry = FavoritesUi.entryFor(channel("s1", name = "US - CNN", epg = "cnn.us"), now = 42L)
        assertEquals("s1", entry.streamId)
        assertEquals("US - CNN", entry.name)
        assertEquals("cnn.us", entry.epgChannelId)
        assertEquals(42L, entry.addedAt)
    }

    @Test
    fun `entryFor keeps a null epg id`() {
        assertNull(FavoritesUi.entryFor(channel("s1")).epgChannelId)
    }

    @Test
    fun `favorite action label flips with current state`() {
        assertEquals("Add to Favorites", FavoritesUi.favoriteActionLabel(isFavorite = false))
        assertEquals("Remove from Favorites", FavoritesUi.favoriteActionLabel(isFavorite = true))
    }

    @Test
    fun `confirmation label matches the resulting state`() {
        assertEquals("Added to Favorites", FavoritesUi.confirmationLabel(nowFavorite = true))
        assertEquals("Removed from Favorites", FavoritesUi.confirmationLabel(nowFavorite = false))
    }
}
