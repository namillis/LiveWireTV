package com.livewire.tv.feature.home

import com.livewire.tv.feature.epg.data.EpgCachePolicy
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.epg.guideWindowStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Home's guide window. Home opens first, so the cache entry it creates must serve the screens
 * opened after it without another download. The other screens' windows are copied here from
 * their view models; a change there that this no longer covers fails the test.
 */
class HomeGuideWindowTest {

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val minute = TimeUnit.MINUTES.toMillis(1)

    /** 2026-11-09 14:00 UTC. */
    private val h14 = 1_794_232_800_000L

    private fun covered(entry: EpgWindow, request: EpgWindow) =
        EpgCachePolicy.covers(entry.startMs, entry.endMs, request)

    @Test fun `Home asks for now to 6 hours ahead`() {
        assertEquals(EpgWindow(h14, h14 + 6 * hour), HomeViewModel.guideWindow(h14))
    }

    @Test fun `the pool sends 2 hours back and 14 or 15 ahead for it`() {
        for ((at, future) in listOf(h14 to 14, h14 + 46 * minute to 15)) {
            val parsed = EpgCachePolicy.downloadWindow(HomeViewModel.guideWindow(at))!!
            assertEquals(EpgCachePolicy.PoolGuideRequest(2, future), EpgCachePolicy.poolGuideRequest(parsed, at))
        }
    }

    @Test fun `the entry Home creates serves every other screen for the cache's 3 hours`() {
        for (minuteOfHour in listOf(0, 1, 29, 30, 31, 59)) {
            val opened = h14 + minuteOfHour * minute
            val entry = EpgCachePolicy.downloadWindow(HomeViewModel.guideWindow(opened))!!
            for (later in listOf(0L, minute, hour, 3 * hour)) {
                val now = opened + later
                val screens = buildMap {
                    // The Guide: from guideWindowStart, as wide as the setting (2-8 hours).
                    for (span in 2..8) put("Guide ${span}h", EpgWindow(guideWindowStart(now), guideWindowStart(now) + span * hour))
                    put("Search", EpgWindow(now - 2 * hour, now + 6 * hour))
                    put("Sports", EpgWindow(now - 2 * hour, now + 6 * hour))
                    put("Player", EpgWindow(now - hour, now + 6 * hour))
                    put("Favorites", EpgWindow(now - hour, now + 2 * hour))
                    put("Home again", HomeViewModel.guideWindow(now))
                }
                for ((name, window) in screens) {
                    assertTrue("$name, ${later / minute} min after Home opened at :$minuteOfHour", covered(entry, window))
                }
            }
        }
    }
}
