package com.livewire.tv.feature.providers.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryLookupTest {

    private fun ch(id: String, category: String) = LiveChannel(streamId = id, name = "Channel $id", categoryId = category)

    private val all = listOf(
        ch("1", "news"), ch("2", "sports"), ch("3", "news"), ch("4", "sports"), ch("5", "kids"),
    )

    @Test
    fun `finds the category and its channels in list order`() {
        val match = CategoryLookup.find(all, "4")!!
        assertEquals("sports", match.categoryId)
        assertEquals(listOf("2", "4"), match.channels.map { it.streamId })
    }

    @Test
    fun `a stream that isn't listed returns null so the caller can walk the categories`() {
        assertNull(CategoryLookup.find(all, "99"))
        assertNull(CategoryLookup.find(emptyList(), "1"))
    }

    @Test
    fun `a stream with no category returns null`() {
        assertNull(CategoryLookup.find(all + ch("6", ""), "6"))
    }
}
