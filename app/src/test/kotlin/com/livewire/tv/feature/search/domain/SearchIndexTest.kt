package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    private fun ch(id: String, name: String) = LiveChannel(streamId = id, name = name, categoryId = "c1")
    private fun prog(title: String, desc: String? = null) =
        EpgProgramme(channelId = "x", startMs = 0, stopMs = 1, title = title, description = desc)
    private fun game(away: String, home: String, league: String) = SportsGame(
        id = "g", leagueId = league, startTimeMs = 0, status = GameStatus(GameState.PRE),
        home = TeamSide("1", "Home", home, isHome = true), away = TeamSide("2", "Away", away),
    )

    @Test fun `empty query yields nothing`() {
        assertTrue(SearchIndex().search("").isEmpty())
        assertTrue(SearchIndex().search("   ").isEmpty())
    }

    @Test fun `ranks exact over prefix over substring for channels`() {
        val idx = SearchIndex(channels = listOf(ch("1", "ESPN News"), ch("2", "ESPN"), ch("3", "The ESPN Zone")))
        val r = idx.search("espn")
        assertEquals("2", r.first().channel!!.streamId) // exact wins
        assertEquals(setOf("1", "2", "3"), r.map { it.channel!!.streamId }.toSet())
    }

    @Test fun `title beats description for programmes`() {
        val idx = SearchIndex(programmes = listOf(prog("Cooking Masters"), prog("The News", "a cooking segment")))
        val r = idx.search("cooking")
        assertEquals(2, r.size)
        assertEquals("Cooking Masters", r.first().title)
    }

    @Test fun `matches games by abbreviation and league`() {
        val idx = SearchIndex(games = listOf(game("BOS", "LAL", "nba"), game("NYY", "BOS", "mlb")))
        assertEquals(2, idx.search("bos").size)
        assertEquals(1, idx.search("nba").size)
    }

    @Test fun `respects the limit`() {
        val many = (0 until 100).map { ch("$it", "Channel $it") }
        assertEquals(10, SearchIndex(channels = many).search("channel", limit = 10).size)
    }

    @Test fun `scores are non-increasing`() {
        val idx = SearchIndex(
            channels = listOf(ch("1", "Sports Center")),
            programmes = listOf(prog("Sports Tonight")),
            games = listOf(game("BOS", "LAL", "sports")),
        )
        val r = idx.search("sports")
        for (i in 1 until r.size) assertTrue(r[i - 1].score >= r[i].score)
    }
}
