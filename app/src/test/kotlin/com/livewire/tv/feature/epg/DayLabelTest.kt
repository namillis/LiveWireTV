package com.livewire.tv.feature.epg

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DayLabelTest {
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun pin() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.timeInMillis

    @Test fun `same calendar day has no label`() {
        assertNull(dayLabel(at(2, 0, 5), now = at(2, 16, 45)))
    }

    @Test fun `an earlier day is named with weekday and date`() {
        assertEquals("Thu 1 Oct", dayLabel(at(1, 19), now = at(2, 16, 45)))
        // Just before local midnight is still the previous day.
        assertEquals("Thu 1 Oct", dayLabel(at(1, 23, 59), now = at(2, 0, 1)))
    }
}
