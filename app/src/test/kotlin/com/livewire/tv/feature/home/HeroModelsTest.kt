package com.livewire.tv.feature.home

import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroModelsTest {

    private val min = 60_000L
    private fun prog(startMin: Long, stopMin: Long) =
        EpgProgramme("c", startMin * min, stopMin * min, "p")

    @Test fun `min left formats whole minutes and ends soon in the last minute`() {
        val now = 10 * min
        assertEquals("50 min left", minutesLeftLabel(prog(0, 60), now))
        assertEquals("ends soon", minutesLeftLabel(prog(0, 10), now + 30_000L))
        assertEquals("", minutesLeftLabel(null, now))
    }

    @Test fun `min elapsed clamps at zero before the programme starts`() {
        val now = 24 * min
        assertEquals("24 min elapsed", minutesElapsedLabel(prog(0, 60), now))
        assertEquals("0 min elapsed", minutesElapsedLabel(prog(30, 60), now))
    }

    @Test fun `duration is whole minutes and never negative`() {
        assertEquals(60L, durationMinutes(prog(0, 60)))
        assertEquals(0L, durationMinutes(null))
    }
}
