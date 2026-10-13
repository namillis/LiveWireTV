package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class GuideLaneTest {

    private val min = TimeUnit.MINUTES.toMillis(1)
    private val start = 1_000_000L * min
    private val span = 240 * min

    private fun prog(fromMin: Int, toMin: Int, title: String = "p$fromMin") =
        EpgProgramme("c", start + fromMin * min, start + toMin * min, title)

    private fun total(cells: List<LaneCell>) = cells.sumOf { it.gapMinutes + it.minutes }

    @Test fun `no programmes gives no cells`() {
        assertTrue(laneCells(emptyList(), start, span).isEmpty())
    }

    @Test fun `programme that started before the window is clipped to the window start`() {
        val cells = laneCells(listOf(prog(-30, 30)), start, span)
        assertEquals(0, cells[0].gapMinutes)
        assertEquals(30, cells[0].minutes)
    }

    @Test fun `gap before a late-starting programme is kept so rows stay aligned`() {
        val cells = laneCells(listOf(prog(60, 90)), start, span)
        assertEquals(60, cells[0].gapMinutes)
        assertEquals(30, cells[0].minutes)
    }

    @Test fun `lane never exceeds the window`() {
        val cells = laneCells(listOf(prog(0, 120), prog(120, 400)), start, span)
        assertEquals(240, total(cells))
    }

    @Test fun `overlapping programmes are pushed right, not stacked`() {
        val cells = laneCells(listOf(prog(0, 60), prog(30, 90)), start, span)
        assertEquals(60, cells[0].minutes)
        assertEquals(0, cells[1].gapMinutes)
        assertEquals(30, cells[1].minutes)
    }

    @Test fun `very short programmes get a minimum width`() {
        val cells = laneCells(listOf(prog(0, 1)), start, span)
        assertEquals(5, cells[0].minutes)
    }

    @Test fun `programmes outside the window are dropped`() {
        assertTrue(laneCells(listOf(prog(-90, -30), prog(300, 360)), start, span).isEmpty())
    }

    @Test fun `cell starts count the gaps and cells before them`() {
        val cells = laneCells(listOf(prog(0, 30), prog(60, 90), prog(90, 200)), start, span)
        assertEquals(listOf(0, 60, 90), cellStartMinutes(cells))
    }

    @Test fun `text stays put when the cell starts on screen`() {
        assertEquals(0, stickyTextShift(cellStartPx = 500, scrollPx = 200, textAreaPx = 600, minTextPx = 100))
    }

    @Test fun `text follows the left edge when the cell starts off screen`() {
        // A game that began 2 hours before the visible timeline: its title moves into view.
        assertEquals(300, stickyTextShift(cellStartPx = 0, scrollPx = 300, textAreaPx = 1_000, minTextPx = 100))
    }

    @Test fun `text keeps a minimum width and then scrolls off with the cell`() {
        assertEquals(500, stickyTextShift(cellStartPx = 0, scrollPx = 800, textAreaPx = 600, minTextPx = 100))
    }

    @Test fun `a cell narrower than the minimum never shifts`() {
        assertEquals(0, stickyTextShift(cellStartPx = 0, scrollPx = 50, textAreaPx = 80, minTextPx = 100))
    }
}
