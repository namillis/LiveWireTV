package com.livewire.tv.feature.epg

import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ★ Favourites guide filter: the category count must equal the rows the grid will show
 * (task §4: don't show 5 for 4), and filtering keeps only the favourite rows that are loaded.
 */
class GuideFavoritesFilterTest {

    private fun row(id: String, name: String = id) =
        GuideRow(LiveChannel(streamId = id, name = name, categoryId = "c"), emptyList())

    private val rows = listOf(row("a"), row("b"), row("c"), row("d"))

    @Test
    fun `favorites category count equals the available favourite rows, not the stored ids`() {
        // 5 favourite ids stored, but only 3 are among the loaded rows: the count must be 3.
        val favIds = setOf("a", "b", "c", "gone1", "gone2")
        val category = favoritesCategory(rows, favIds)
        assertEquals(FAVORITES_CATEGORY_ID, category.id)
        assertEquals("Favourites", category.name)
        assertEquals(3, category.channelCount)
    }

    @Test
    fun `favorites filter keeps only favourite rows, in loaded order`() {
        val filtered = filterGuideRows(rows, FAVORITES_CATEGORY_ID, query = "", favoriteIds = setOf("c", "a"))
        assertEquals(listOf("a", "c"), filtered.map { it.channel.streamId })
    }

    @Test
    fun `favorites filter with no favourites is empty`() {
        assertEquals(emptyList<GuideRow>(), filterGuideRows(rows, FAVORITES_CATEGORY_ID, "", emptySet()))
    }

    @Test
    fun `favorites filter also honours the keyword`() {
        val named = listOf(row("a", "CNN"), row("b", "BBC"), row("c", "CBS"))
        val filtered = filterGuideRows(named, FAVORITES_CATEGORY_ID, query = "cn", favoriteIds = setOf("a", "b", "c"))
        assertEquals(listOf("a"), filtered.map { it.channel.streamId })
    }

    @Test
    fun `null category still returns every row`() {
        assertEquals(rows, filterGuideRows(rows, categoryId = null, query = ""))
    }
}
