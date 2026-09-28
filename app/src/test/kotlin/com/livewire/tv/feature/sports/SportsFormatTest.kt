package com.livewire.tv.feature.sports

import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsFormatTest {

    private fun game(
        state: GameState,
        clock: String? = null,
        detail: String? = null,
        networks: List<String> = emptyList(),
    ) = SportsGame(
        id = "g1", leagueId = "nfl", startTimeMs = 0L,
        status = GameStatus(state = state, displayClock = clock, detail = detail),
        home = TeamSide("1", "Bills", "BUF", isHome = true),
        away = TeamSide("2", "Chargers", "LAC"),
        broadcastNetworks = networks,
    )

    @Test fun `live is live only in progress`() {
        assertTrue(SportsFormat.isLive(game(GameState.IN_PROGRESS)))
        assertFalse(SportsFormat.isLive(game(GameState.PRE)))
        assertFalse(SportsFormat.isLive(game(GameState.FINAL)))
        assertFalse(SportsFormat.isLive(game(GameState.UNKNOWN)))
    }

    @Test fun `in-progress prefers the display clock`() {
        assertEquals("10:06 – 1st", SportsFormat.statusLine(game(GameState.IN_PROGRESS, clock = "10:06 – 1st")))
    }

    @Test fun `in-progress falls back to detail then LIVE`() {
        assertEquals("Halftime", SportsFormat.statusLine(game(GameState.IN_PROGRESS, detail = "Halftime")))
        assertEquals("LIVE", SportsFormat.statusLine(game(GameState.IN_PROGRESS)))
        assertEquals("LIVE", SportsFormat.statusLine(game(GameState.IN_PROGRESS, clock = "  ")))
    }

    @Test fun `pre shows the scheduled detail or a default`() {
        assertEquals("Sun, Sep 27 at 1:00 PM", SportsFormat.statusLine(game(GameState.PRE, detail = "Sun, Sep 27 at 1:00 PM")))
        assertEquals("Scheduled", SportsFormat.statusLine(game(GameState.PRE)))
    }

    @Test fun `final shows the detail or a default`() {
        assertEquals("Final/OT", SportsFormat.statusLine(game(GameState.FINAL, detail = "Final/OT")))
        assertEquals("Final", SportsFormat.statusLine(game(GameState.FINAL)))
    }

    @Test fun `score label uses an em dash before a score exists`() {
        assertEquals("14", SportsFormat.scoreLabel(14))
        assertEquals("0", SportsFormat.scoreLabel(0))
        assertEquals("—", SportsFormat.scoreLabel(null))
    }

    @Test fun `scores show only for in-progress and final games`() {
        assertTrue(SportsFormat.showScores(game(GameState.IN_PROGRESS)))
        assertTrue(SportsFormat.showScores(game(GameState.FINAL)))
        assertFalse(SportsFormat.showScores(game(GameState.PRE)))
        assertFalse(SportsFormat.showScores(game(GameState.UNKNOWN)))
    }

    @Test fun `networks line joins networks or notes none`() {
        assertEquals("On: FOX", SportsFormat.networksLine(game(GameState.PRE, networks = listOf("FOX"))))
        assertEquals("On: FOX, ABC", SportsFormat.networksLine(game(GameState.PRE, networks = listOf("FOX", "ABC"))))
        assertEquals("No broadcast network listed", SportsFormat.networksLine(game(GameState.PRE)))
    }

    @Test fun `empty state names the network with no channel`() {
        assertEquals("No channel found for Prime Video", SportsFormat.noChannelMessage(game(GameState.PRE, networks = listOf("Prime Video"))))
        assertEquals("No channel found for this game", SportsFormat.noChannelMessage(game(GameState.PRE)))
    }
}
