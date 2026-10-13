package com.livewire.tv.feature.epg.domain

import com.livewire.tv.feature.providers.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelProgrammesTest {
    private val min = 60_000L
    private val t0 = 1_791_850_500_000L

    private fun prog(id: String, start: Long, stop: Long, title: String) = EpgProgramme(id, start, stop, title)

    // The borrowed guide: a postgame show where the game should be.
    private val sky = "nova.skysportsmainevent.uk"
    private val guideRows = mapOf(
        sky to listOf(
            prog(sky, t0 + 60 * min, t0 + 120 * min, "NFL Postgame"),
            prog(sky, t0, t0 + 60 * min, "NFL Football"),
        ),
    )
    private val guide: (String) -> List<EpgProgramme> = { guideRows[it].orEmpty() }

    private val event = EpgProgramme("99", t0 + 30 * min, t0 + 210 * min, "NFL · Week 5", endIsEstimate = true)
    private val game = LiveChannel(streamId = "99", name = "Bills @ Rams", epgChannelId = sky, categoryId = "187", poolEvent = event)
    private val plain = LiveChannel(streamId = "5", name = "Sky Sports Main Event", epgChannelId = sky, categoryId = "3")

    @Test
    fun `an event channel lists only its event, never the borrowed guide`() {
        assertEquals(listOf(event), ChannelProgrammes.listings(game, guide))
        assertTrue(ChannelProgrammes.later(game, t0, guide).isEmpty())
        assertNull(ChannelProgrammes.next(game, t0, guide))
    }

    @Test
    fun `an event is on now before it starts, while it runs and past its estimated end`() {
        listOf(t0, t0 + 31 * min, t0 + 300 * min).forEach {
            assertSame("at $it", event, ChannelProgrammes.now(game, it, guide))
        }
    }

    @Test
    fun `any other channel reads the guide as before`() {
        assertEquals("NFL Football", ChannelProgrammes.now(plain, t0 + 10 * min, guide)?.title)
        assertEquals("NFL Postgame", ChannelProgrammes.next(plain, t0 + 10 * min, guide)?.title)
        assertEquals(listOf("NFL Postgame"), ChannelProgrammes.later(plain, t0 + 10 * min, guide).map { it.title })
        assertNull(ChannelProgrammes.now(plain, t0 + 200 * min, guide))
    }

    @Test
    fun `a channel with no guide id and no event has nothing`() {
        val bare = LiveChannel(streamId = "7", name = "Local", categoryId = "1")
        assertTrue(ChannelProgrammes.listings(bare, guide).isEmpty())
        assertNull(ChannelProgrammes.now(bare, t0, guide))
        assertNull(ChannelProgrammes.now(plain, t0, ChannelProgrammes.from(null)))
        // The event needs no guide at all.
        assertSame(event, ChannelProgrammes.now(game, t0, ChannelProgrammes.from(null)))
    }
}
