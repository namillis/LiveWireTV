package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderStorageReorderTest {

    private fun p(id: String) =
        ProviderConfig(id = id, name = id, baseUrl = "http://x", username = "u", password = "p")

    @Test
    fun `moves a middle provider to the front`() {
        val out = ProviderStorage.reorderActiveFirst(listOf(p("a"), p("b"), p("c")), "c")
        assertEquals(listOf("c", "a", "b"), out?.map { it.id })
    }

    @Test
    fun `returns null when already first`() {
        assertNull(ProviderStorage.reorderActiveFirst(listOf(p("a"), p("b")), "a"))
    }

    @Test
    fun `returns null when id absent`() {
        assertNull(ProviderStorage.reorderActiveFirst(listOf(p("a"), p("b")), "z"))
    }

    @Test
    fun `does not mutate the input list`() {
        val list = listOf(p("a"), p("b"), p("c"))
        ProviderStorage.reorderActiveFirst(list, "b")
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }
}
