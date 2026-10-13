package com.livewire.tv.feature.epg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EventTimesTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // Monday 12 October 2026, 8:15 PM EDT.
    private val start = at(12, 20, 15)
    private val event = EpgProgramme("1", start, start + 3 * 3_600_000L, "NFL · Week 5", endIsEstimate = true)

    @Test
    fun `names the start, never the end`() {
        assertEquals("Starts 8:15 PM", EventTimes.label(event, at(12, 19, 50), zone))
        assertEquals("Started 8:15 PM", EventTimes.label(event, at(12, 21, 0), zone))
        assertEquals("Started 8:15 PM", EventTimes.label(event, start, zone))
        // Past the estimated end it still says when it started.
        assertEquals("Started 8:15 PM", EventTimes.label(event, at(12, 23, 59), zone))
    }

    @Test
    fun `adds the day when the start isn't today`() {
        assertEquals("Started Mon 8:15 PM", EventTimes.label(event, at(13, 0, 30), zone))
        assertEquals("Starts Mon 8:15 PM", EventTimes.label(event, at(11, 22, 0), zone))
    }

    @Test
    fun `only an event that hasn't started is upcoming`() {
        assertTrue(EventTimes.isUpcoming(event, start - 1))
        assertFalse(EventTimes.isUpcoming(event, start))
        assertFalse(EventTimes.isUpcoming(event.copy(endIsEstimate = false), start - 1))
    }
}
