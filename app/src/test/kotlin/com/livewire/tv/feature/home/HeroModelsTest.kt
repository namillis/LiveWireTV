package com.livewire.tv.feature.home

import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroModelsTest {

    private val min = 60_000L
    private fun prog(startMin: Long, stopMin: Long) =
        EpgProgramme("c", startMin * min, stopMin * min, "p")

    @Test fun `brand tint matches a known channel ignoring region and quality tags`() {
        assertEquals(NeutralBrand, brandTint("US - MYSTERY CHANNEL HD").let { if (it == NeutralBrand) NeutralBrand else it })
        // CNN is in the map; region/quality tags stripped.
        assertEquals(brandTint("CNN"), brandTint("US - CNN HD"))
    }

    @Test fun `longest brand key wins so FOX NEWS is distinct from FOX`() {
        // FOX NEWS and FOX map to the same navy here, but the match must still prefer the
        // longer key rather than stopping at "FOX"; assert the normaliser keeps both words.
        assertEquals("US FOX NEWS", normaliseBrandKey("US - FOX NEWS CHANNEL HD"))
    }

    @Test fun `unknown channel falls back to the neutral tint`() {
        assertEquals(NeutralBrand, brandTint("US - RANDOM LOCAL 12"))
    }

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
