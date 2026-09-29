package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The guide opens with the amber ring on the programme airing NOW, not the first (often
 * already-ended) cell. [initialFocusCellIndex] chooses that cell from the same [laneCells]
 * the row renders, so the ring and the details band agree on open.
 */
class GuideInitialFocusTest {

    private val min = TimeUnit.MINUTES.toMillis(1)
    private val start = 1_000_000L * min
    private val span = 240 * min
    // "Now" sits 45 min into the window (the 30-min lead-in means the on-now programme is
    // not the first cell).
    private val now = start + 45 * min

    private fun prog(fromMin: Int, toMin: Int, title: String = "p$fromMin") =
        EpgProgramme("c", start + fromMin * min, start + toMin * min, title)

    private fun cells(vararg p: EpgProgramme) = laneCells(p.toList(), start, span)

    @Test fun `empty cell list falls back to index 0`() {
        assertEquals(0, initialFocusCellIndex(emptyList(), now))
    }

    @Test fun `picks the cell airing now, not the earlier one`() {
        // 0-30 has ended, 30-60 is on now, 60-120 is upcoming. Now = +45.
        val c = cells(prog(0, 30), prog(30, 60), prog(60, 120))
        assertEquals(1, initialFocusCellIndex(c, now))
    }

    @Test fun `on-now cell that started before the window is index 0`() {
        // A single programme spanning the lead-in and now: clipped to start, still on now.
        val c = cells(prog(-30, 90))
        assertEquals(0, initialFocusCellIndex(c, now))
    }

    @Test fun `no programme on now falls back to the first cell`() {
        // Everything is in the past or the future relative to now (+45): a gap covers now.
        val c = cells(prog(0, 20), prog(60, 120))
        assertEquals(0, initialFocusCellIndex(c, now))
    }

    @Test fun `later on-now cell is chosen when earlier cells are all past`() {
        val laterNow = start + 100 * min
        val c = cells(prog(0, 30), prog(30, 60), prog(60, 90), prog(90, 150))
        // 90-150 airs at +100.
        assertEquals(3, initialFocusCellIndex(c, laterNow))
    }
}
