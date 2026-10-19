package com.livewire.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeShiftRenewalTest {

    private val start = 1_000_000L
    private val end = start + 5 * 60_000L

    private fun window(token: Int, renewal: Boolean, error: String? = null) =
        ArchiveState(startMs = start, endMs = end, timeShift = true, renewal = renewal, token = token, error = error)

    @Test fun `a renewal opens at a pending scrub target`() {
        assertEquals(start + 60_000, TimeShiftRenewal.startAt(start + 60_000, start + 4 * 60_000, end))
    }

    @Test fun `with nothing pending a renewal opens at the playback position`() {
        assertEquals(start + 4 * 60_000, TimeShiftRenewal.startAt(null, start + 4 * 60_000, end))
    }

    @Test fun `an unknown position renews from the window end`() {
        assertEquals(end, TimeShiftRenewal.startAt(null, 0, end))
    }

    @Test fun `a renewal is opening until its token is opened`() {
        assertTrue(TimeShiftRenewal.isOpening(window(token = 7, renewal = true), openedToken = 6))
        assertFalse(TimeShiftRenewal.isOpening(window(token = 7, renewal = true), openedToken = 7))
    }

    @Test fun `a request the user made is never a renewal opening`() {
        assertFalse(TimeShiftRenewal.isOpening(window(token = 7, renewal = false), openedToken = 6))
    }

    @Test fun `a renewal that failed to resolve is not opening`() {
        val failed = window(token = 7, renewal = true, error = "This programme isn't in the archive.")
        assertFalse(TimeShiftRenewal.isOpening(failed, openedToken = 6))
    }

    @Test fun `presses measure from the new window start while a renewal opens`() {
        val opening = window(token = 7, renewal = true)
        // The old stream is still playing near its end, but the new one will start at `start`.
        assertEquals(start, TimeShiftRenewal.playheadMs(opening, openedToken = 6, positionMs = end - 20_000))
    }

    @Test fun `otherwise presses measure from the playback position`() {
        val open = window(token = 7, renewal = true)
        assertEquals(start + 90_000, TimeShiftRenewal.playheadMs(open, openedToken = 7, positionMs = start + 90_000))
        assertEquals(start, TimeShiftRenewal.playheadMs(open, openedToken = 7, positionMs = 0))
    }
}
