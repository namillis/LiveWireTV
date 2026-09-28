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

    @Test fun `matches provider names that use punctuation separators`() {
        val names = listOf("US - NBC HD \u25C9", "US|NBC CHICAGO", "UK: BBC ONE", "US - CNBC HD")
        val index = SearchIndex(channels = names.mapIndexed { i, n -> LiveChannel(i.toString(), n, categoryId = "c") })
        val hits = index.search("nbc").map { it.title }
        assertEquals(listOf("US - NBC HD \u25C9", "US|NBC CHICAGO", "US - CNBC HD"), hits)
        assertTrue(index.search("NBC HD").first().title.startsWith("US - NBC HD"))
    }

    // ── Separator / placeholder channels (provider category dividers) ──

    @Test fun `excludes separator marker channels`() {
        assertTrue(SearchIndex.isSeparatorName("##### FOX #####"))
        assertTrue(SearchIndex.isSeparatorName("===== SPORTS ====="))
        assertTrue(SearchIndex.isSeparatorName("#####"))
        assertTrue(SearchIndex.isSeparatorName("____"))
        assertTrue(SearchIndex.isSeparatorName("*** ***"))
        assertTrue(SearchIndex.isSeparatorName("   "))
        // Real channels — including hyphenated provider names — are NOT separators.
        assertFalse(SearchIndex.isSeparatorName("US - FOX HD"))
        assertFalse(SearchIndex.isSeparatorName("FOX 26 Houston"))
        assertFalse(SearchIndex.isSeparatorName("US|NBC CHICAGO"))
    }

    @Test fun `separator channels never appear in results`() {
        val idx = SearchIndex(
            channels = listOf(
                ch("sep1", "##### FOX ALABAMA #####"),
                ch("sep2", "##### FOX ARIZONA #####"),
                ch("real", "US - FOX HD"),
            ),
        )
        val r = idx.search("fox")
        assertEquals(listOf("real"), r.map { it.channel!!.streamId })
    }

    @Test fun `ranks the exact network first then affiliate then spin-off`() {
        // Real provider names seen for a 'fox' search on a large US panel.
        val idx = SearchIndex(
            channels = listOf(
                ch("sepA", "##### FOX ALABAMA #####"),
                ch("news", "US - FOX NEWS CHANNEL HD"),
                ch("fs1", "US - FOX SPORTS 1 HD"),
                ch("aff", "FOX 26 Houston"),
                ch("fox", "US - FOX HD"),
            ),
        )
        val order = idx.search("fox").map { it.channel!!.streamId }
        // Separator excluded entirely.
        assertFalse(order.contains("sepA"))
        // Exact network first.
        assertEquals("fox", order.first())
        // Affiliate (with a channel number) outranks the same-brand spin-offs.
        assertTrue(order.indexOf("aff") < order.indexOf("news"))
        assertTrue(order.indexOf("aff") < order.indexOf("fs1"))
    }

    @Test fun `many channel matches do not crowd out programmes and games`() {
        val channels = (0 until 80).map { ch("$it", "US - FOX $it") }
        val results = SearchIndex(
            channels = channels,
            programmes = listOf(prog("The Masked Singer on FOX")),
            games = listOf(game("FOX", "GB", "nfl")),
        ).search("fox")
        assertEquals(SearchIndex.CHANNEL_LIMIT, results.count { it.kind == SearchResultKind.CHANNEL })
        assertEquals(1, results.count { it.kind == SearchResultKind.PROGRAMME })
        assertEquals(1, results.count { it.kind == SearchResultKind.GAME })
    }

    @Test fun `duplicate channel names appear once`() {
        val results = SearchIndex(
            channels = listOf(ch("1", "US - FOX HD"), ch("2", "US - FOX HD"), ch("3", "FOX 26 Houston")),
        ).search("fox")
        assertEquals(1, results.count { it.title == "US - FOX HD" })
    }
}
