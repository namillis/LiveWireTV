package com.livewire.tv.feature.favorites.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fallback focus-target picker used to return focus to the card that opened an overlay,
 * after a favourite toggle may have added/removed the Favourites rail and shifted indices.
 */
class FavoritesFocusTest {

    // rails[0] = Favourites (fav index 0), rails[1..] = category rails.
    private val rails = listOf(
        listOf("cnn", "fox"),   // Favourites rail
        listOf("cnn", "nbc"),   // News
        listOf("espn", "fox"),  // Sports
    )

    @Test
    fun `prefers the item in a category rail over the favourites rail`() {
        val t = FavoritesFocus.restoreTarget(rails, favoritesRailIndex = 0, streamId = "cnn")
        assertEquals(FavoritesFocus.Target(1, 0), t) // News rail, not the Favourites rail at 0
    }

    @Test
    fun `finds an item that only exists in the favourites rail`() {
        val onlyFav = listOf(listOf("gone"), listOf("nbc"))
        val t = FavoritesFocus.restoreTarget(onlyFav, favoritesRailIndex = 0, streamId = "gone")
        assertEquals(FavoritesFocus.Target(0, 0), t)
    }

    @Test
    fun `picks the right card index within a category rail`() {
        val t = FavoritesFocus.restoreTarget(rails, favoritesRailIndex = 0, streamId = "fox")
        assertEquals(FavoritesFocus.Target(2, 1), t) // Sports rail, second card
    }

    @Test
    fun `falls back to the first card of the first non-empty rail when the item is gone`() {
        val t = FavoritesFocus.restoreTarget(rails, favoritesRailIndex = 0, streamId = "missing")
        assertEquals(FavoritesFocus.Target(0, 0), t)
    }

    @Test
    fun `skips leading empty rails in the fallback`() {
        val withEmpty = listOf(emptyList<String>(), listOf("a", "b"))
        val t = FavoritesFocus.restoreTarget(withEmpty, favoritesRailIndex = -1, streamId = "missing")
        assertEquals(FavoritesFocus.Target(1, 0), t)
    }

    @Test
    fun `null stream id falls back to the first card`() {
        val t = FavoritesFocus.restoreTarget(rails, favoritesRailIndex = 0, streamId = null)
        assertEquals(FavoritesFocus.Target(0, 0), t)
    }

    @Test
    fun `no rails yields null`() {
        assertNull(FavoritesFocus.restoreTarget(emptyList(), favoritesRailIndex = -1, streamId = "x"))
        assertNull(FavoritesFocus.restoreTarget(listOf(emptyList()), favoritesRailIndex = -1, streamId = "x"))
    }
}
