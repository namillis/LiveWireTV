package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.epg.domain.EpgProgramme
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

    @Test fun `a one-letter channel name does not match a longer network`() {
        // Real provider data: "MX - E! FHD" tokenizes to ["e"], which the squashed
        // fallback used to find inside "primevideo".
        val m = SportsRepository.matchChannels(
            game(listOf("Prime Video")),
            listOf(ch("1", "MX - E! FHD"), ch("2", "US - PRIME VIDEO HD")),
        )
        assertEquals(listOf("2"), m.map { it.channel.streamId })
    }

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

    @Test fun `channels listed twice under the same name appear once, keeping the first`() {
        // This provider lists "US - FOX HD" twice (a backup stream under the same name).
        val m = SportsRepository.matchChannels(
            game(listOf("FOX")),
            listOf(ch("1", "US - FOX HD"), ch("2", "US - FOX HD "), ch("3", "US - FOX 26 HOUSTON HD")),
        )
        assertEquals(listOf("1", "3"), m.map { it.channel.streamId })
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

    @Test fun `returns the full ranked list, best-first (Search picker relies on all matches)`() {
        // Search's SearchViewModel.channelsForGame exposes this whole list to the shared
        // channel picker — not just the top one — so a game with several carrying channels
        // must yield every match, best-ranked first.
        val channels = listOf(
            ch("a5", "US - FOX 5 NEW YORK HD"), // affiliate
            ch("fx", "US - FOX HD"),            // exact — should rank first
            ch("cnn", "US - CNN HD"),           // unrelated — excluded
        )
        val matches = SportsRepository.matchChannels(game(listOf("FOX")), channels)
        assertEquals(2, matches.size)
        assertEquals("fx", matches[0].channel.streamId) // exact network ranks above the affiliate
        assertEquals("a5", matches[1].channel.streamId)
    }

    // Real provider names (teck-tv, 2026-09-29) for NYR @ BOS on ESPN.
    private val rangersAtBruins = SportsGame(
        id = "g2", leagueId = "nhl", startTimeMs = 0L,
        status = GameStatus(GameState.PRE),
        home = TeamSide("1", "Boston Bruins", "BOS", isHome = true, shortName = "Bruins"),
        away = TeamSide("2", "New York Rangers", "NYR", shortName = "Rangers"),
        broadcastNetworks = listOf("ESPN"),
    )

    @Test fun `a channel naming both teams ranks above the network`() {
        val m = SportsRepository.matchChannels(
            rangersAtBruins,
            listOf(
                ch("1", "US - ESPN HD ◉"),
                ch("2", "US - NHL GAME 03 : NEW YORK RANGERS @ BOSTON BRUINS SEP 29 – 8:00 PM ET / 1:00 AM UK"),
                ch("3", "US - ESPN PLUS 61 : NHL: NYR @ BOS • MTL @ TOR SEP 29 – 8:00 PM ET / SEP 30 – 1:00 AM UK"),
            ),
        )
        assertEquals(listOf("2", "3", "1"), m.map { it.channel.streamId })
        assertEquals(SportsRepository.GAME_LISTING, m[0].matchedNetwork)
        assertEquals("ESPN", m[2].matchedNetwork)
    }

    @Test fun `a listing by nickname matches a streaming-only game`() {
        val game = SportsGame(
            id = "g3", leagueId = "mlb", startTimeMs = 0L,
            status = GameStatus(GameState.PRE),
            home = TeamSide("1", "New York Yankees", "NYY", isHome = true, shortName = "Yankees"),
            away = TeamSide("2", "Boston Red Sox", "BOS", shortName = "Red Sox"),
            broadcastNetworks = listOf("Prime Video"),
        )
        val m = SportsRepository.matchChannels(
            game,
            listOf(
                ch("1", "US - PRIME 01 :"),
                ch("2", "US - PEACOCK 03 : RED SOX @ YANKEES SEP 29 – 8:00 PM ET / SEP 30 – 1:00 AM UK"),
            ),
        )
        assertEquals(listOf("2"), m.map { it.channel.streamId })
    }

    @Test fun `a channel naming only one team is not a game listing`() {
        val m = SportsRepository.matchChannels(
            rangersAtBruins,
            listOf(
                ch("1", "US - NHL TEAMS - BOSTON BRUINS"),
                ch("2", "US - NHL GAME 02 : MONTREAL CANADIENS @ TORONTO MAPLE LEAFS SEP 29"),
            ),
        )
        assertTrue(m.isEmpty())
    }

    // Guide matching. The game starts at T; listings are placed around it.
    private val t = 1_000_000_000L
    private val hour = 3_600_000L
    private val guideGame = rangersAtBruins.copy(startTimeMs = t)

    private fun epgCh(id: String, name: String, epg: String) =
        LiveChannel(streamId = id, name = name, categoryId = "c1", epgChannelId = epg)

    private fun prog(epg: String, title: String, desc: String? = null, start: Long = t, hours: Long = 3) =
        EpgProgramme(channelId = epg, startMs = start, stopMs = start + hours * hour, title = title, description = desc)

    @Test fun `a guide listing naming both teams ranks first`() {
        val m = SportsRepository.matchChannels(
            guideGame,
            listOf(
                epgCh("1", "US - NHL GAME 03 : NEW YORK RANGERS @ BOSTON BRUINS", "nhl3"),
                epgCh("2", "US - ESPN 1 HD", "espn1"),
            ),
            mapOf("espn1" to listOf(prog("espn1", "New York Rangers at Boston Bruins"))),
        )
        assertEquals(listOf("2", "1"), m.map { it.channel.streamId })
        assertEquals(SportsRepository.GUIDE_LISTING, m[0].matchedNetwork)
    }

    @Test fun `a game title with the teams in the description matches`() {
        val m = SportsRepository.matchChannels(
            guideGame.copy(broadcastNetworks = emptyList()),
            listOf(epgCh("1", "US - NESN HD", "nesn")),
            mapOf("nesn" to listOf(prog("nesn", "NHL Hockey", "Boston Bruins host the New York Rangers."))),
        )
        assertEquals(listOf("1"), m.map { it.channel.streamId })
    }

    @Test fun `a description alone does not match a non-game show`() {
        // A news show previewing the game names both teams but does not carry it.
        val m = SportsRepository.matchChannels(
            guideGame.copy(broadcastNetworks = emptyList()),
            listOf(epgCh("1", "US - ESPN NEWS HD", "news")),
            mapOf("news" to listOf(prog("news", "SportsCenter", "Rangers and Bruins preview."))),
        )
        assertTrue(m.isEmpty())
    }

    @Test fun `a network match showing another programme ranks below one with no guide`() {
        val m = SportsRepository.matchChannels(
            guideGame,
            listOf(epgCh("1", "US - ESPN HD", "espn"), epgCh("2", "US - ESPN FHD", "none")),
            mapOf("espn" to listOf(prog("espn", "SportsCenter"))),
        )
        assertEquals(listOf("2", "1"), m.map { it.channel.streamId })
    }

    @Test fun `a listing outside the game's time does not count`() {
        val m = SportsRepository.matchChannels(
            guideGame.copy(broadcastNetworks = emptyList()),
            listOf(epgCh("1", "US - ESPN 1 HD", "espn1")),
            mapOf("espn1" to listOf(prog("espn1", "New York Rangers at Boston Bruins", start = t + 5 * hour))),
        )
        assertTrue(m.isEmpty())
    }

    @Test fun `a shared guide id confirms only the network's own channels`() {
        // Real provider data: ESPN, ESPN 1, ESPN ACC Network and ESPN SEC Network all use
        // the guide id "espn.us", so its listing cannot vouch for the ACC or SEC channel.
        val listing = mapOf("espn.us" to listOf(prog("espn.us", "Chicago Blackhawks at Vegas Golden Knights")))
        val game = guideGame.copy(
            home = TeamSide("1", "Vegas Golden Knights", "VGK", isHome = true, shortName = "Golden Knights"),
            away = TeamSide("2", "Chicago Blackhawks", "CHI", shortName = "Blackhawks"),
        )
        val m = SportsRepository.matchChannels(
            game,
            listOf(
                epgCh("1", "US - ESPN ACC NETWORK HD ◉", "espn.us"),
                epgCh("2", "US - ESPN HD ◉", "espn.us"),
                epgCh("3", "US - ESPN 1 HD ◉", "espn.us"),
                epgCh("4", "US - ESPN SEC NETWORK HD ◉", "espn.us"),
            ),
            listing,
        )
        assertEquals(listOf("2", "3"), m.take(2).map { it.channel.streamId })
        assertTrue(m.take(2).all { it.matchedNetwork == SportsRepository.GUIDE_LISTING })
        assertTrue(m.drop(2).none { it.matchedNetwork == SportsRepository.GUIDE_LISTING })
    }
}
