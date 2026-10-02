package com.livewire.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSeekControllerTest {

    private val start = 1_000_000_000L
    private val end = start + 3_600_000L // 1h window

    private fun ctrl(openAt: Long = start, debounce: Long = 600) =
        ArchiveSeekController(windowStartMs = start, windowEndMs = end, openStartMs = openAt, seekDebounceMs = debounce)

    @Test fun `absolute position is open start plus player position, clamped`() {
        val c = ctrl(openAt = start + 60_000)
        assertEquals(start + 60_000 + 5_000, c.absolutePosition(5_000))
        // clamps to window end
        assertEquals(end, c.absolutePosition(999_999_999))
        // clamps to window start (negative-ish handled by caller coercion, but clamp guards)
        assertEquals(start + 60_000, c.absolutePosition(0))
    }

    @Test fun `elapsed and duration`() {
        val c = ctrl()
        assertEquals(3_600_000L, c.durationMs)
        assertEquals(0L, c.elapsedMs(start))
        assertEquals(600_000L, c.elapsedMs(start + 600_000))
        assertEquals(3_600_000L, c.elapsedMs(end + 10_000)) // clamped
    }

    @Test fun `clamp keeps targets inside the window`() {
        val c = ctrl()
        assertEquals(start, c.clamp(start - 5_000))
        assertEquals(end, c.clamp(end + 5_000))
        assertEquals(start + 100, c.clamp(start + 100))
    }

    @Test fun `seek within buffer on a seekable stream is native`() {
        val c = ctrl()
        // current at +10s, target +20s, buffered 30s ahead
        val d = c.resolveSeek(
            targetMs = start + 20_000, currentAbsoluteMs = start + 10_000,
            seekable = true, bufferedAheadMs = 30_000, bufferedBehindMs = 10_000,
        )
        assertTrue(d is ArchiveSeekController.SeekDecision.Native)
        d as ArchiveSeekController.SeekDecision.Native
        assertEquals(20_000L, d.relativePositionMs) // target - openStart
        assertEquals(start + 20_000, d.absoluteTargetMs)
    }

    @Test fun `seek beyond buffer reopens`() {
        val c = ctrl()
        val d = c.resolveSeek(
            targetMs = start + 900_000, currentAbsoluteMs = start + 10_000,
            seekable = true, bufferedAheadMs = 30_000, bufferedBehindMs = 10_000,
        )
        assertTrue(d is ArchiveSeekController.SeekDecision.Reopen)
        assertEquals(start + 900_000, d.absoluteTargetMs)
    }

    @Test fun `non-seekable stream always reopens even within buffer`() {
        val c = ctrl()
        val d = c.resolveSeek(
            targetMs = start + 15_000, currentAbsoluteMs = start + 10_000,
            seekable = false, bufferedAheadMs = 60_000, bufferedBehindMs = 60_000,
        )
        assertTrue(d is ArchiveSeekController.SeekDecision.Reopen)
    }

    @Test fun `reopen target is clamped into the window`() {
        val c = ctrl()
        val d = c.resolveSeek(
            targetMs = end + 500_000, currentAbsoluteMs = start + 10_000,
            seekable = false, bufferedAheadMs = 0, bufferedBehindMs = 0,
        )
        assertEquals(end, d.absoluteTargetMs)
    }

    @Test fun `first seek acts immediately`() {
        val c = ctrl(debounce = 600)
        assertEquals(start + 30_000, c.requestSeek(start + 30_000, nowMs = 0))
    }

    @Test fun `rapid seeks within debounce collapse to one pending target`() {
        val c = ctrl(debounce = 600)
        assertEquals(start + 10_000, c.requestSeek(start + 10_000, nowMs = 0)) // acts now
        assertNull(c.requestSeek(start + 20_000, nowMs = 100)) // debounced
        assertNull(c.requestSeek(start + 30_000, nowMs = 200)) // debounced, overwrites
        assertNull(c.requestSeek(start + 40_000, nowMs = 300)) // debounced, overwrites
        // Only the latest survives as the pending target.
        assertEquals(start + 40_000, c.takePending())
        assertNull(c.takePending()) // cleared
    }

    @Test fun `a seek after the debounce window acts immediately again`() {
        val c = ctrl(debounce = 600)
        assertEquals(start, c.requestSeek(start, nowMs = 0))
        assertNull(c.requestSeek(start + 5_000, nowMs = 100))
        // 700ms after the last request -> acts now
        assertEquals(start + 9_000, c.requestSeek(start + 9_000, nowMs = 800))
    }

    @Test fun `onReopenedAt updates the open start so positions remap`() {
        val c = ctrl(openAt = start)
        c.onReopenedAt(start + 600_000)
        assertEquals(start + 600_000, c.openStartMs)
        // player reports 5s into the reopened stream
        assertEquals(start + 605_000, c.absolutePosition(5_000))
    }
}
