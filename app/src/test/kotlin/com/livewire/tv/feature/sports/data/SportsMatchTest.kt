package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMatchTest {

    private fun game(networks: List<String>) = SportsGame(
        id = "g1", leagueId = "nba", startTimeMs = 0L,
        status = GameStatus(GameState.PRE),
        home = TeamSide("1", "Lakers", "LAL", isHome = true),
        away = TeamSide("2", "Celtics", "BOS"),
        broadcastNetworks = networks,
    )

    private fun ch(id: String, name: String) = LiveChannel(streamId = id, name = name, categoryId = "c1")

    @Test fun `matches a network to a channel with HD suffix`() {
        val m = SportsRepository.matchChannels(game(listOf("ESPN")), listOf(ch("1", "ESPN HD"), ch("2", "CNN")))
        assertEquals(1, m.size)
        assertEquals("1", m[0].channel.streamId)
        assertEquals("ESPN", m[0].matchedNetwork)
    }

    @Test fun `matches multiple networks`() {
        val m = SportsRepository.matchChannels(
            game(listOf("ESPN", "TNT")),
            listOf(ch("1", "ESPN"), ch("2", "TNT 4K"), ch("3", "Food Network")),
        )
        assertEquals(setOf("1", "2"), m.map { it.channel.streamId }.toSet())
    }

    @Test fun `does not match unrelated channels`() {
        val m = SportsRepository.matchChannels(game(listOf("ABC")), listOf(ch("1", "HBO"), ch("2", "Discovery")))
        assertTrue(m.isEmpty())
    }

    @Test fun `deduplicates a channel matched by two networks`() {
        val m = SportsRepository.matchChannels(game(listOf("ESPN", "ESPN")), listOf(ch("1", "ESPN")))
        assertEquals(1, m.size)
    }

    @Test fun `empty networks yields no matches`() {
        assertTrue(SportsRepository.matchChannels(game(emptyList()), listOf(ch("1", "ESPN"))).isEmpty())
    }

    @Test fun `exact network and affiliates rank above same-brand spin-offs`() {
        // Real channel names seen from an Xtream provider, in provider order.
        val channels = listOf(
            ch("w", "US - FOX WEATHER HD"),
            ch("n", "US - FOX NEWS LIVE NOW HD"),
            ch("nc", "US - FOX NEWS CHANNEL HD ◉ "),
            ch("bk", "US - FOX NEWS CHANNEL [BK] HD"),
            ch("a5", "US - FOX 5 NEW YORK HD"),
            ch("fx", "US - FOX HD"),
        )
        val ids = SportsRepository.matchChannels(game(listOf("FOX")), channels).map { it.channel.streamId }
        assertEquals("fx", ids[0])
        assertEquals("a5", ids[1])
        assertEquals(setOf("w", "n", "nc", "bk"), ids.drop(2).toSet())
    }

    @Test fun `country prefix does not block an exact match`() {
        val m = SportsRepository.matchChannels(
            game(listOf("CBS")),
            listOf(ch("1", "US - CBS SPORTS NETWORK"), ch("2", "UK| CBS")),
        )
        assertEquals("2", m[0].channel.streamId)
    }

    @Test fun `squashed network names still match as a last resort`() {
        val m = SportsRepository.matchChannels(game(listOf("ESPN2")), listOf(ch("1", "US - ESPN 2 HD")))
        assertEquals(1, m.size)
    }

    @Test fun `tokenize strips country prefix, feed tags and quality tags`() {
        assertEquals(listOf("fox", "news", "channel"), SportsRepository.tokenize("US - FOX NEWS CHANNEL [BK] HD"))
        // A leading word that is not a country code is kept.
        assertEquals(listOf("nbc", "sports", "boston"), SportsRepository.tokenize("NBC - Sports Boston"))
    }
}
