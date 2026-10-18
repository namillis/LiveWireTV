package com.livewire.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchUpScrubberTest {

    private val start = 1_000_000L
    private val end = start + 3 * 60 * 60_000L // a 3-hour programme
    private val playhead = start + 10 * 60_000L

    private fun CatchUpScrubber.tap(forward: Boolean, at: Long, repeat: Boolean = false, position: Long = playhead) =
        press(forward, repeat, at, position, start, end)

    @Test fun `a first press steps 30 s forward or 10 s back from the playhead`() {
        assertEquals(playhead + 30_000, CatchUpScrubber().tap(forward = true, at = 0))
        assertEquals(playhead - 10_000, CatchUpScrubber().tap(forward = false, at = 0))
    }

    @Test fun `presses add up from the target, not from where playback is`() {
        val s = CatchUpScrubber()
        s.tap(true, at = 0)
        // Playback hasn't moved to the target yet; the next press still builds on the target.
        assertEquals(playhead + 60_000, s.tap(true, at = 300))
        assertEquals(playhead + 60_000, s.targetMs)
    }

    @Test fun `steps grow while presses keep coming the same way`() {
        val s = CatchUpScrubber()
        val steps = (1..16).map { i ->
            val before = s.targetMs ?: playhead
            s.tap(true, at = i * 200L)!! - before
        }
        assertEquals(
            listOf(30_000L, 30_000L, 60_000L, 60_000L, 60_000L, 120_000L, 120_000L, 120_000L, 120_000L,
                300_000L, 300_000L, 300_000L, 300_000L, 300_000L, 600_000L, 600_000L),
            steps,
        )
    }

    @Test fun `a pause between presses starts again from the small step`() {
        val s = CatchUpScrubber()
        repeat(6) { s.tap(true, at = it * 200L) }
        val before = s.targetMs!!
        assertEquals(before + 30_000, s.tap(true, at = 5 * 200L + CatchUpScrubber.STREAK_GAP_MS + 1))
    }

    @Test fun `changing direction starts again from the small step`() {
        val s = CatchUpScrubber()
        repeat(6) { s.tap(true, at = it * 200L) }
        val before = s.targetMs!!
        assertEquals(before - 10_000, s.tap(false, at = 1_300))
    }

    @Test fun `a held key takes at most one step per repeat interval`() {
        val s = CatchUpScrubber()
        s.tap(true, at = 0)
        assertNull(s.tap(true, at = 50, repeat = true))
        assertNull(s.tap(true, at = 100, repeat = true))
        assertEquals(playhead + 60_000, s.tap(true, at = 150, repeat = true))
    }

    @Test fun `dropped repeats keep a held key's streak alive`() {
        val s = CatchUpScrubber()
        // Holding for 3 s with Android-style 50 ms repeats from 400 ms: the streak never breaks.
        s.tap(true, at = 0)
        var t = 400L
        while (t <= 3_000L) { s.tap(true, at = t, repeat = true); t += 50 }
        // The tap plus one step every 150 ms from 400 ms to 2950 ms: 19 steps, 87 minutes.
        assertEquals(playhead + 87 * 60_000L, s.targetMs)
    }

    @Test fun `the target stays inside the programme`() {
        val s = CatchUpScrubber()
        assertEquals(start, s.tap(false, at = 0, position = start + 5_000))
        assertEquals(start, s.tap(false, at = 100))
        val f = CatchUpScrubber()
        assertEquals(end, f.tap(true, at = 0, position = end - 5_000))
    }

    @Test fun `taking the target clears it, and the next press starts from the playhead`() {
        val s = CatchUpScrubber()
        s.tap(true, at = 0)
        assertEquals(playhead + 30_000, s.take())
        assertNull(s.targetMs)
        val newPlayhead = playhead + 30_000
        assertEquals(newPlayhead + 30_000, s.tap(true, at = 2_000, position = newPlayhead))
    }

    @Test fun `cancel drops the target and the step size`() {
        val s = CatchUpScrubber()
        repeat(8) { s.tap(true, at = it * 200L) }
        s.cancel()
        assertNull(s.targetMs)
        assertEquals(playhead + 30_000, s.tap(true, at = 1_700))
    }
}
