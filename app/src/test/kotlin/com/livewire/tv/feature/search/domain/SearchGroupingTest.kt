package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SearchGroupingTest {

    private val ny = TimeZone.getTimeZone("America/New_York")

    private fun channelResult(id: String, name: String, score: Double) =
        SearchResult(SearchResultKind.CHANNEL, name, score, channel = LiveChannel(id, name, categoryId = "c"))

    private fun programmeResult(title: String, startMs: Long, stopMs: Long, score: Double) =
        SearchResult(
            SearchResultKind.PROGRAMME, title, score,
            programme = EpgProgramme(channelId = "x", startMs = startMs, stopMs = stopMs, title = title),
        )

    private fun gameResult(away: String, home: String, score: Double) = SearchResult(
        SearchResultKind.GAME, "$away @ $home", score,
        game = SportsGame(
            id = "$away$home", leagueId = "nfl", startTimeMs = 0, status = GameStatus(GameState.PRE),
            home = TeamSide("1", "Home", home, isHome = true), away = TeamSide("2", "Away", away),
        ),
    )

    // Times are epoch millis in America/New_York for stable formatting.
    // 2026-01-01 20:00 EST = 1767315600000; +1h = 1767319200000; 21:00 = start of next.
    private val eightPm = 1_767_315_600_000L
    private val ninePm = 1_767_319_200_000L
    private val tenPm = 1_767_322_800_000L
    // A reference "now" just before 8pm so the eightPm–ninePm programme counts as upcoming.
    private val beforeEight = eightPm - 60_000L

    @Test fun `group splits by kind preserving order`() {
        val results = listOf(
            channelResult("1", "FOX", 1.0),
            gameResult("DAL", "NYG", 0.9),
            channelResult("2", "FOX NEWS", 0.8),
            programmeResult("Masked Singer", eightPm, ninePm, 0.7),
        )
        val g = SearchGrouping.group(results, beforeEight)
        assertEquals(listOf("FOX", "FOX NEWS"), g.channels.map { it.title })
        assertEquals(listOf("Masked Singer"), g.programmes.map { it.title })
        assertEquals(listOf("DAL @ NYG"), g.games.map { it.title })
        assertEquals(4, g.total)
    }

    @Test fun `empty group reports empty and blank count line`() {
        val g = SearchGrouping.group(emptyList(), beforeEight)
        assertTrue(g.isEmpty())
        assertEquals("", SearchGrouping.countLine(g))
        assertTrue(g.nonEmptySections().isEmpty())
    }

    @Test fun `count line lists only non-empty sections in order`() {
        val g = SearchGrouping.group(
            listOf(
                channelResult("1", "FOX", 1.0),
                channelResult("2", "FOX NEWS", 0.9),
                programmeResult("Show", eightPm, ninePm, 0.5),
                gameResult("DAL", "NYG", 0.4),
            ),
            beforeEight,
        )
        assertEquals("4 results · 2 channels · 1 on TV · 1 games", SearchGrouping.countLine(g))
    }

    @Test fun `count line omits sections with no results`() {
        val g = SearchGrouping.group(listOf(channelResult("1", "FOX", 1.0)), beforeEight)
        assertEquals("1 result · 1 channels", SearchGrouping.countLine(g))
    }

    @Test fun `total is singular result but section counts are literal`() {
        val g = SearchGrouping.group(listOf(gameResult("DAL", "NYG", 1.0)), beforeEight)
        assertEquals("1 result · 1 games", SearchGrouping.countLine(g))
    }

    @Test fun `non-empty sections follow channels then on-tv then sports`() {
        val g = SearchGrouping.group(
            listOf(
                gameResult("DAL", "NYG", 0.4),
                programmeResult("Show", eightPm, ninePm, 0.5),
                channelResult("1", "FOX", 1.0),
            ),
            beforeEight,
        )
        assertEquals(
            listOf(SearchSection.CHANNELS, SearchSection.ON_TV, SearchSection.SPORTS),
            g.nonEmptySections(),
        )
    }

    @Test fun `drops programmes that have already ended`() {
        val g = SearchGrouping.group(
            listOf(
                programmeResult("Ended", eightPm, ninePm, 0.9),   // stops at 9pm
                programmeResult("Upcoming", ninePm, tenPm, 0.5),  // starts at 9pm
            ),
            now = ninePm + 60_000L, // just after 9pm
        )
        assertEquals(listOf("Upcoming"), g.programmes.map { it.title })
    }

    @Test fun `orders programmes now-first then by start time`() {
        val g = SearchGrouping.group(
            listOf(
                // ranked highest but starts latest and is not airing yet
                programmeResult("Later", tenPm, tenPm + 3_600_000L, 0.9),
                // airing right now (8–9pm), lower rank
                programmeResult("Airing", eightPm, ninePm, 0.4),
                // upcoming at 9pm, mid rank
                programmeResult("Soon", ninePm, tenPm, 0.6),
            ),
            now = eightPm + 60_000L, // 8:01pm
        )
        // Airing-now first, then earliest upcoming start.
        assertEquals(listOf("Airing", "Soon", "Later"), g.programmes.map { it.title })
    }

    @Test fun `airing programme pill is NOW`() {
        val p = EpgProgramme(channelId = "x", startMs = eightPm, stopMs = ninePm, title = "T")
        assertTrue(SearchGrouping.isAiring(p, eightPm + 60_000))
        assertEquals("NOW", SearchGrouping.programmePill(p, eightPm + 60_000, ny))
    }

    @Test fun `later programme pill is its start time`() {
        val p = EpgProgramme(channelId = "x", startMs = ninePm, stopMs = tenPm, title = "T")
        assertFalse(SearchGrouping.isAiring(p, eightPm + 60_000))
        assertEquals("9:00 PM", SearchGrouping.programmePill(p, eightPm + 60_000, ny))
    }

    @Test fun `time range drops the shared meridiem on the start`() {
        val p = EpgProgramme(channelId = "x", startMs = eightPm, stopMs = ninePm, title = "T")
        assertEquals("8:00 – 9:00 PM", SearchGrouping.programmeTimeRange(p, ny))
    }

    @Test fun `time range keeps both meridiems when they differ`() {
        // 11:30 AM EST = 1767285000000, 12:30 PM = 1767288600000
        val p = EpgProgramme(channelId = "x", startMs = 1_767_285_000_000L, stopMs = 1_767_288_600_000L, title = "T")
        assertEquals("11:30 AM – 12:30 PM", SearchGrouping.programmeTimeRange(p, ny))
    }
}
