package com.livewire.tv.feature.sports

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.search.data.PendingSearch
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SportsListTest {
    private val hour = 3_600_000L
    private val t = 1_800_000_000_000L

    private val leagues = listOf(
        SportsLeague("nfl", "NFL", "football"),
        SportsLeague("nba", "NBA", "basketball"),
        SportsLeague("nhl", "NHL", "hockey"),
    )

    private fun game(id: String, league: String, state: GameState, start: Long = t, networks: List<String> = listOf("ESPN")) = SportsGame(
        id = id, leagueId = league, startTimeMs = start, status = GameStatus(state),
        home = TeamSide("h", "Seattle Seahawks", "SEA", isHome = true, shortName = "Seahawks"),
        away = TeamSide("a", "Chicago Bears", "CHI", shortName = "Bears"),
        broadcastNetworks = networks,
    )

    private fun ch(id: String, name: String, event: EpgProgramme? = null) =
        LiveChannel(streamId = id, name = name, categoryId = "live", poolEvent = event)

    private fun event(title: String, start: Long = t) =
        EpgProgramme(channelId = "x", startMs = start, stopMs = start + 3 * hour, title = title, endIsEstimate = true)

    // --- sections ---

    @Test fun `games fall into on now, coming up and finished, in that order`() {
        val games = listOf(
            game("f", "nfl", GameState.FINAL),
            game("p", "nba", GameState.PRE),
            game("l", "nhl", GameState.IN_PROGRESS),
        )
        val arranged = SportsList.arrange(games, leagues, null)
        assertEquals(listOf(SportsSection.ON_NOW, SportsSection.COMING_UP, SportsSection.FINISHED), arranged.map { it.first })
        assertEquals(listOf("l", "p", "f"), arranged.flatMap { s -> s.second.map { it.id } })
    }

    @Test fun `live games keep league order, coming up runs soonest first, finished most recent first`() {
        val games = listOf(
            game("nhl-live", "nhl", GameState.IN_PROGRESS, start = t - hour),
            game("nfl-live", "nfl", GameState.IN_PROGRESS, start = t),
            game("late", "nfl", GameState.PRE, start = t + 3 * hour),
            game("soon", "nhl", GameState.PRE, start = t + hour),
            game("old", "nfl", GameState.FINAL, start = t - 30 * hour),
            game("recent", "nba", GameState.FINAL, start = t - 5 * hour),
        )
        val ids = SportsList.arrange(games, leagues, null).associate { it.first to it.second.map { g -> g.id } }
        assertEquals(listOf("nfl-live", "nhl-live"), ids[SportsSection.ON_NOW])
        assertEquals(listOf("soon", "late"), ids[SportsSection.COMING_UP])
        assertEquals(listOf("recent", "old"), ids[SportsSection.FINISHED])
    }

    @Test fun `a league filter keeps only that league, and empty sections are left out`() {
        val games = listOf(game("a", "nfl", GameState.IN_PROGRESS), game("b", "nba", GameState.PRE))
        val arranged = SportsList.arrange(games, leagues, "nba")
        assertEquals(listOf(SportsSection.COMING_UP), arranged.map { it.first })
        assertEquals(listOf("b"), arranged.single().second.map { it.id })
    }

    @Test fun `chips start with All and list only leagues that have games`() {
        val games = listOf(game("a", "nfl", GameState.IN_PROGRESS), game("b", "nfl", GameState.FINAL), game("c", "nhl", GameState.PRE))
        val chips = SportsList.chips(games, leagues)
        assertEquals(listOf(null, "nfl", "nhl"), chips.map { it.id })
        assertEquals(listOf(3, 2, 1), chips.map { it.count })
    }

    // --- sources ---

    private val liveGame = game("g", "nfl", GameState.IN_PROGRESS)
    private val espn = ChannelMatch(ch("e", "US - ESPN"), SportsRepository.GUIDE_LISTING)

    @Test fun `Live Now wins over a channel match`() {
        val pool = ch("p", "Chicago Bears at Seattle Seahawks")
        assertEquals(GameSource.LiveNow(pool), SportsList.source(liveGame, pool, listOf(espn), settled = true))
    }

    @Test fun `a channel match is used when there is no Live Now channel`() {
        assertEquals(GameSource.Channel(espn), SportsList.source(liveGame, null, listOf(espn), settled = false))
    }

    @Test fun `no matches stays Finding until the guide has settled`() {
        assertEquals(GameSource.Finding, SportsList.source(liveGame, null, null, settled = false))
        assertEquals(GameSource.Finding, SportsList.source(liveGame, null, emptyList(), settled = false))
        assertEquals(GameSource.None, SportsList.source(liveGame, null, emptyList(), settled = true))
    }

    @Test fun `a finished game has no source`() {
        assertNull(SportsList.source(game("f", "nfl", GameState.FINAL), ch("p", "x"), listOf(espn), settled = true))
    }

    @Test fun `options put Live Now first and list each channel once`() {
        val pool = ch("p", "Chicago Bears at Seattle Seahawks")
        val options = SportsList.options(pool, listOf(ChannelMatch(pool, SportsRepository.GAME_LISTING), espn))
        assertEquals(listOf("p", "e"), options.map { it.channel.streamId })
        assertTrue(options[0].liveNow)
        assertEquals(SportsList.POOL_REASON, options[0].reason)
        assertEquals("Guide lists this game", options[1].reason)
    }

    @Test fun `reasons read in plain words`() {
        assertEquals("Named after this game", SportsList.reason(ChannelMatch(ch("1", "x"), SportsRepository.GAME_LISTING)))
        assertEquals("Matches ESPN", SportsList.reason(ChannelMatch(ch("1", "x"), "ESPN")))
    }

    // --- Live Now matching ---

    @Test fun `a Live Now event naming both teams matches`() {
        val pool = listOf(ch("1", "Portland Trail Blazers @ Denver Nuggets", event("Portland Trail Blazers at Denver Nuggets")),
            ch("2", "Chicago Bears at Seattle Seahawks", event("Chicago Bears at Seattle Seahawks")))
        assertEquals("2", SportsRepository.liveNowChannel(liveGame, pool)?.streamId)
    }

    @Test fun `a Live Now event by nickname matches`() {
        val pool = listOf(ch("1", "NFL 01", event("Bears @ Seahawks")))
        assertEquals("1", SportsRepository.liveNowChannel(liveGame, pool)?.streamId)
    }

    @Test fun `a Live Now event naming one team does not match`() {
        val pool = listOf(ch("1", "Bears postgame", event("Chicago Bears Postgame Live")))
        assertNull(SportsRepository.liveNowChannel(liveGame, pool))
    }

    @Test fun `a Live Now event far from the game's start does not match`() {
        // Same teams a week later: a rematch, not this game.
        val pool = listOf(ch("1", "Chicago Bears at Seattle Seahawks", event("Chicago Bears at Seattle Seahawks", start = t + 7 * 24 * hour)))
        assertNull(SportsRepository.liveNowChannel(liveGame, pool))
    }

    @Test fun `a Live Now channel with no event matches by its name`() {
        val pool = listOf(ch("1", "NFL GAME 01 : CHICAGO BEARS @ SEATTLE SEAHAWKS"))
        assertEquals("1", SportsRepository.liveNowChannel(liveGame, pool)?.streamId)
    }

    // --- labels ---

    @Test fun `search query is the away team's nickname, since Search matches one phrase`() {
        assertEquals("Bears", SportsList.searchQuery(liveGame))
        assertEquals("Chicago Bears", SportsList.searchQuery(liveGame.copy(away = liveGame.away.copy(shortName = ""))))
    }

    @Test fun `start label shows the time today and the day otherwise`() {
        val zone = ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.of(2026, 11, 2, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val tonight = java.time.ZonedDateTime.of(2026, 11, 2, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
        val thursday = java.time.ZonedDateTime.of(2026, 11, 5, 20, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("10:30 PM", SportsList.startLabel(tonight, now, zone))
        assertEquals("Thu 8:15 PM", SportsList.startLabel(thursday, now, zone))
    }

    @Test fun `title uses nicknames`() {
        assertEquals("Bears @ Seahawks", SportsList.title(liveGame))
    }

    @Test fun `a pending search is taken once`() {
        val pending = PendingSearch()
        pending.set("  Bears Seahawks ")
        assertEquals("Bears Seahawks", pending.take())
        assertNull(pending.take())
    }
}
