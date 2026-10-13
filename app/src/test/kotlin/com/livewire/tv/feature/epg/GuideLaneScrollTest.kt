package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Up/Down keep the guide lane still when the landing cell is on screen, so the timeline does
 * not drift into the past and the empty "No programme information" cell (which spans the
 * whole window) does not scroll a cold-opened guide two days back. Left/Right step to the
 * neighbouring programme even when it is scrolled out of view.
 */
class GuideLaneScrollTest {

    private val viewport = 1000f

    @Test fun `empty cell spanning the whole window stays put`() {
        // Window starts 2 days back: the cell's leading edge is far left of the viewport.
        assertEquals(0f, laneScrollDistance(-40_000f, 60_000f, viewport, stepping = false))
    }

    @Test fun `up and down keep the lane still for any cell on screen`() {
        // Down onto "Teleshopping 7:35-10:00" while browsing 9:45: it started off the left edge.
        assertEquals(0f, laneScrollDistance(-300f, 900f, viewport, stepping = false))
        // A short programme that began earlier, only its end on screen.
        assertEquals(0f, laneScrollDistance(-250f, 300f, viewport, stepping = false))
        assertEquals(0f, laneScrollDistance(300f, 200f, viewport, stepping = false))
    }

    @Test fun `left and right keep still only for a fully visible cell`() {
        assertEquals(0f, laneScrollDistance(300f, 200f, viewport, stepping = true))
        assertEquals(0f, laneScrollDistance(0f, viewport, viewport, stepping = true))
        // Peeking in at either edge: the platform rule brings its start into view.
        assertNull(laneScrollDistance(900f, 300f, viewport, stepping = true))
        assertNull(laneScrollDistance(-200f, 300f, viewport, stepping = true))
        assertNull(laneScrollDistance(-1_500f, 1_700f, viewport, stepping = true))
    }

    @Test fun `cell entirely off screen uses the platform rule`() {
        assertNull(laneScrollDistance(1_200f, 2_000f, viewport, stepping = false))
        assertNull(laneScrollDistance(-3_000f, 2_000f, viewport, stepping = true))
    }

    @Test fun `focus landing on an empty cell before programmes load is not a user move`() {
        assertFalse(isUserFocusMove(programme = null, programmesLoaded = false))
    }

    @Test fun `focus moves after programmes load count as user moves`() {
        assertTrue(isUserFocusMove(programme = null, programmesLoaded = true))
        assertTrue(isUserFocusMove(programme = EpgProgramme("c", 0L, 60_000L, "p"), programmesLoaded = false))
    }

    private val min = 60_000L
    private val ws = 1_000_000L * min
    private val span = 48 * 60 * min
    private fun prog(from: Int, to: Int) = EpgProgramme("c", ws + from * min, ws + to * min, "p$from")

    @Test fun `left from a long programme lands on the one before it`() {
        val before = prog(0, 60)
        val long = prog(60, 400)
        val cells = laneCells(listOf(before, long), ws, span)
        val now = ws + 390 * min
        val anchor = neighbourAnchor(cells, long, forward = false, nowMs = now, windowStartMs = ws)!!
        assertEquals(0, cellIndexForAnchor(cells, anchor))
    }

    @Test fun `right lands on the next programme and left from the first cell has no neighbour`() {
        val a = prog(0, 30)
        val b = prog(30, 90)
        val cells = laneCells(listOf(a, b), ws, span)
        val now = ws + 10 * min
        assertEquals(1, cellIndexForAnchor(cells, neighbourAnchor(cells, a, forward = true, nowMs = now, windowStartMs = ws)!!))
        assertNull(neighbourAnchor(cells, a, forward = false, nowMs = now, windowStartMs = ws))
        assertNull(neighbourAnchor(cells, b, forward = true, nowMs = now, windowStartMs = ws))
    }

    @Test fun `overlapping listings still reach the next cell`() {
        // b starts before a ends; laneCells pushes it right, and an anchor at b's start
        // would resolve to a.
        val a = prog(0, 60)
        val b = prog(45, 120)
        val cells = laneCells(listOf(a, b), ws, span)
        val anchor = neighbourAnchor(cells, a, forward = true, nowMs = ws + 500 * min, windowStartMs = ws)!!
        assertEquals(1, cellIndexForAnchor(cells, anchor))
    }
}
