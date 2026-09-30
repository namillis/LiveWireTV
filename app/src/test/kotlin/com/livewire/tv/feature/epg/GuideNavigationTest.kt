package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Guide category/keyword filters, and the anchor rules Up/Down use to pick a cell. */
class GuideNavigationTest {

    private val min = TimeUnit.MINUTES.toMillis(1)
    private val start = 1_000_000L * min
    private val span = 240 * min
    private val now = start + 54 * min // 9:24 on a 8:30 window, say

    private fun prog(fromMin: Int, toMin: Int) = EpgProgramme("c", start + fromMin * min, start + toMin * min, "p$fromMin")
    private fun cells(vararg p: EpgProgramme) = laneCells(p.toList(), start, span)

    private fun channel(id: String, name: String, category: String) =
        LiveChannel(streamId = id, name = name, categoryId = category)
    private fun row(id: String, name: String, category: String) = GuideRow(channel(id, name, category), emptyList())

    // ---- Up/Down keep the time being browsed ----

    @Test fun `moving down the live column lands on the programme airing now`() {
        // Next row: 0-30 ended, 30-120 on now, 120-180 later.
        assertEquals(1, cellIndexForAnchor(cells(prog(0, 30), prog(30, 120), prog(120, 180)), now))
    }

    @Test fun `an empty row keeps the anchor, so the row after it is back on now`() {
        // Empty row: one cell, and the anchor is unchanged because Up/Down never set it.
        assertEquals(0, cellIndexForAnchor(emptyList(), now))
        // The row after: now airs in the second cell, not the late-evening one.
        assertEquals(1, cellIndexForAnchor(cells(prog(0, 50), prog(50, 70), prog(70, 200)), now))
    }

    @Test fun `browsing a later slot stays on that slot`() {
        val elevenPm = start + 150 * min
        assertEquals(2, cellIndexForAnchor(cells(prog(0, 60), prog(60, 120), prog(120, 180)), elevenPm))
    }

    @Test fun `a gap under the anchor picks the nearest programme`() {
        // Nothing airs at +54; 60-90 starts 6 min later, 0-30 ended 24 min before.
        assertEquals(1, cellIndexForAnchor(cells(prog(0, 30), prog(60, 90)), now))
    }

    @Test fun `landing on an on-air cell anchors at now, not at its start`() {
        assertEquals(now, anchorForCell(prog(30, 120), now, start))
    }

    @Test fun `landing on a later cell anchors at its start`() {
        assertEquals(start + 120 * min, anchorForCell(prog(120, 180), now, start))
    }

    @Test fun `landing on an empty cell keeps the current anchor`() {
        assertNull(anchorForCell(null, now, start))
    }

    // ---- filters ----

    private val rows = listOf(
        row("1", "US - ESPN HD", "sports"),
        row("2", "US - CNN HD", "news"),
        row("3", "US - ESPNEWS", "sports"),
        row("4", "US - FOX HD", "ent"),
    )

    @Test fun `no filter keeps every row`() {
        assertEquals(rows, filterGuideRows(rows, null, "  "))
    }

    @Test fun `keyword matches anywhere in the name, ignoring case`() {
        assertEquals(listOf("1", "3"), filterGuideRows(rows, null, " espn ").map { it.channel.streamId })
    }

    @Test fun `category and keyword combine`() {
        assertEquals(listOf("3"), filterGuideRows(rows, "sports", "news").map { it.channel.streamId })
    }

    @Test fun `categories keep provider order, count channels and drop empty ones`() {
        val cats = guideCategories(
            listOf(ProviderCategory("news", "US - News"), ProviderCategory("empty", "Header"), ProviderCategory("sports", "US - Sports")),
            rows.map { it.channel },
        )
        assertEquals(listOf("US - News" to 1, "US - Sports" to 2), cats.map { it.name to it.channelCount })
    }
}
