package com.livewire.tv.feature.home

import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeListsTest {

    private fun ch(id: String) = LiveChannel(streamId = id, name = "Ch $id", categoryId = "c")
    private fun rail(id: String, title: String, vararg ids: String, liveNow: Boolean = false) =
        ChannelRail(id, title, ids.map(::ch), isLiveNow = liveNow)

    private val live = rail("1", "Live Now", "e1", "e2", liveNow = true)
    private val fav = rail(HomeLists.FAVORITES_ID, "Favourites", "a")
    private val recent = rail(HomeLists.RECENT_ID, "Recently watched", "b")
    private val news = rail("7", "News", "n1")
    private val all = rail(HomeLists.ALL_ID, "All channels", "a", "b", "n1")

    @Test fun `lists run Live Now, Favourites, Recent, categories, All`() {
        val lists = HomeLists.lists(live, fav, recent, listOf(news), all)
        assertEquals(listOf("1", HomeLists.FAVORITES_ID, HomeLists.RECENT_ID, "7", HomeLists.ALL_ID), lists.map { it.categoryId })
    }

    @Test fun `Live Now among the first categories is not listed twice`() {
        val asCategory = rail("1", "Live Now", "e1")
        val lists = HomeLists.lists(live, fav, null, listOf(asCategory, news), all)
        assertEquals(1, lists.count { it.categoryId == "1" })
    }

    @Test fun `empty lists are left out`() {
        val lists = HomeLists.lists(rail("1", "Live Now", liveNow = true), null, null, listOf(rail("9", "Empty")), all)
        assertEquals(listOf(HomeLists.ALL_ID), lists.map { it.categoryId })
    }

    @Test fun `default is Live Now when the pool sends it`() {
        assertEquals("1", HomeLists.defaultSelection(HomeLists.lists(live, fav, recent, listOf(news), all), null))
    }

    @Test fun `without Live Now the default falls back Favourites, Recent, All`() {
        assertEquals(HomeLists.FAVORITES_ID, HomeLists.defaultSelection(HomeLists.lists(null, fav, recent, listOf(news), all), null))
        assertEquals(HomeLists.RECENT_ID, HomeLists.defaultSelection(HomeLists.lists(null, null, recent, listOf(news), all), null))
        assertEquals(HomeLists.ALL_ID, HomeLists.defaultSelection(HomeLists.lists(null, null, null, listOf(news), all), null))
        assertEquals("7", HomeLists.defaultSelection(HomeLists.lists(null, null, null, listOf(news), null), null))
        assertNull(HomeLists.defaultSelection(emptyList(), null))
    }

    @Test fun `a remembered list wins while it still exists`() {
        val lists = HomeLists.lists(live, fav, recent, listOf(news), all)
        assertEquals("7", HomeLists.defaultSelection(lists, "7"))
        assertEquals("1", HomeLists.defaultSelection(lists, "gone"))
    }

    @Test fun `Live Now is matched by name, ignoring case and spaces`() {
        assert(HomeLists.isLiveNow(" live now "))
        assert(!HomeLists.isLiveNow("Live Sports"))
    }

    @Test fun `row number is the provider's, else the position`() {
        assertEquals("103", HomeLists.rowNumber(103, 0))
        assertEquals("4", HomeLists.rowNumber(null, 3))
        assertEquals("1,204", HomeLists.count(1204))
    }
}
