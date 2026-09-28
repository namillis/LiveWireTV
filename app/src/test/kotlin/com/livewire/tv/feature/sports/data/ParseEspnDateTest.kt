package com.livewire.tv.feature.sports.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseEspnDateTest {
    // 2026-09-29T00:15Z = Mon 28 Sep 2026, 8:15 PM EDT.
    private val expected = 1_790_640_900_000L

    @Test fun `parses ESPN dates without seconds`() {
        assertEquals(expected, parseEspnDate("2026-09-29T00:15Z"))
    }

    @Test fun `parses dates with seconds and fractions`() {
        assertEquals(expected, parseEspnDate("2026-09-29T00:15:00Z"))
        assertEquals(expected, parseEspnDate("2026-09-29T00:15:00.000Z"))
    }

    @Test fun `parses numeric offsets`() {
        assertEquals(expected, parseEspnDate("2026-09-28T20:15-04:00"))
    }

    @Test fun `returns null for missing or bad input`() {
        assertNull(parseEspnDate(null))
        assertNull(parseEspnDate("not a date"))
    }
}
