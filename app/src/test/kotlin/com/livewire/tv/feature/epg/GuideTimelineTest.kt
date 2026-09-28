package com.livewire.tv.feature.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.concurrent.TimeUnit
import org.junit.Test

class GuideTimelineTest {

    private val min = TimeUnit.MINUTES.toMillis(1)
    private val span = 240 * min

    @Test fun `now-line offset is whole minutes from the window start`() {
        val start = 1_000_000L * min
        assertEquals(24, nowLineMinutes(start + 24 * min, start, span))
    }

    @Test fun `now-line is null before the window and clamped at the window end`() {
        val start = 1_000_000L * min
        assertNull(nowLineMinutes(start - min, start, span))
        assertEquals(240, nowLineMinutes(start + 500 * min, start, span))
    }

    @Test fun `window start snaps to the previous half hour with a lead-in`() {
        // 8:24 PM with a 30-min lead-in snaps to 7:30 PM (previous half-hour before 7:54).
        val eightTwentyFour = TimeUnit.HOURS.toMillis(20) + 24 * min
        val start = guideWindowStart(eightTwentyFour, leadMinutes = 30)
        assertEquals(TimeUnit.HOURS.toMillis(19) + 30 * min, start)
        // Start is always aligned to a half-hour boundary.
        assertEquals(0L, start % (30 * min))
    }

    @Test fun `now sits inside the window given the lead-in`() {
        val now = TimeUnit.HOURS.toMillis(20) + 24 * min
        val start = guideWindowStart(now, leadMinutes = 30)
        val offset = nowLineMinutes(now, start, span)!!
        // 7:30 -> 8:24 is 54 minutes, comfortably inside a 240-min window.
        assertEquals(54, offset)
    }
}
