package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsProvider
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.TeamSide
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** A candidate channel that appears to carry a game, with the network that matched. */
data class ChannelMatch(
    val channel: LiveChannel,
    val matchedNetwork: String,
)

/**
 * Wraps a SportsProvider and adds game→channel fusion (the original app's
 * sports_channel_resolver): match a game's broadcast networks against the user's own
 * live channels so a tapped game jumps to the channel airing it. Kotlin port.
 */
@Singleton
class SportsRepository @Inject constructor(
    private val provider: SportsProvider,
) {
    suspend fun leagues(): List<SportsLeague> = provider.leagues()
    suspend fun scoreboard(leagueId: String): SportsScoreboard = provider.scoreboard(leagueId)

    companion object {
        /**
         * The user's channels that appear to carry [game], best match first. See
         * [ChannelMatcher] for the ranking. Builds a matcher for this one call: a screen that
         * matches several games against the same channels should build one [ChannelMatcher]
         * and reuse it, since preparing the channels and guide is most of the work.
         */
        fun matchChannels(
            game: SportsGame,
            channels: List<LiveChannel>,
            guide: Map<String, List<EpgProgramme>> = emptyMap(),
        ): List<ChannelMatch> = ChannelMatcher(channels, guide).match(game)

        /**
         * The pool's Live Now channel carrying [game], or null. A Live Now channel matches when
         * its event title (or its name, when it has no event) names both teams, and, when it has
         * an event, that event starts within [LIVE_NOW_WINDOW_MS] of the game. The first match in
         * the pool's order wins. [liveNow] is a few dozen channels, so this is cheap.
         */
        fun liveNowChannel(game: SportsGame, liveNow: List<LiveChannel>): LiveChannel? {
            val home = teamNames(game.home)
            val away = teamNames(game.away)
            return liveNow.firstOrNull { ch ->
                val event = ch.poolEvent
                if (event != null && abs(event.startMs - game.startTimeMs) > LIVE_NOW_WINDOW_MS) return@firstOrNull false
                val tokens = tokenize(event?.title ?: ch.name)
                namesBothTeams(tokens, home, away) || (event != null && namesBothTeams(tokenize(ch.name), home, away))
            }
        }

        /** How far a Live Now event's start may sit from the game's: pool times are estimates. */
        private val LIVE_NOW_WINDOW_MS = TimeUnit.HOURS.toMillis(12)

        internal fun namesBothTeams(tokens: List<String>, home: List<List<String>>, away: List<List<String>>): Boolean =
            home.any { indexOfSublist(tokens, it) >= 0 } && away.any { indexOfSublist(tokens, it) >= 0 }

        internal fun indexOfSublist(list: List<String>, sub: List<String>): Int {
            if (sub.isEmpty() || sub.size > list.size) return -1
            for (i in 0..list.size - sub.size) {
                if (list.subList(i, i + sub.size) == sub) return i
            }
            return -1
        }

        /** What the picker shows for a guide-confirmed match ("MATCHED GUIDE LISTING"). */
        const val GUIDE_LISTING = "guide listing"

        /** What the picker shows as the reason for an event-channel match ("MATCHED THIS GAME"). */
        const val GAME_LISTING = "this game"

        /** Words that mark a listing as a live game even when it names no teams. */
        internal val gameWords = setOf(
            "nfl", "nba", "wnba", "mlb", "nhl", "mls", "ncaa", "ncaaf", "ncaab", "epl",
            "football", "basketball", "baseball", "hockey", "soccer", "live",
        )

        /** Whether a guide title looks like a live game ("NHL Hockey", "College Football"). */
        fun looksLikeGame(title: String): Boolean = tokenize(title).any { it in gameWords }

        /** Every way a listing may name [team]: full name, nickname, abbreviation. */
        internal fun teamNames(team: TeamSide): List<List<String>> =
            listOf(team.name, team.shortName, team.abbreviation)
                .map(::tokenize)
                .filter { it.isNotEmpty() }
                .distinct()

        private val qualityTags = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "raw")

        /** Country codes providers prefix names with ("US - ", "UK| ", "CA:"). */
        private val countryCodes = setOf(
            "us", "usa", "uk", "ca", "au", "nz", "ie", "de", "fr", "es", "it", "nl", "pt",
            "br", "mx", "ar", "in", "pk", "tr", "pl", "am", "latam",
        )
        private val leadingCode = Regex("^\\s*([a-z]{2,5})\\s*[-|:]\\s*")
        private val feedTags = Regex("\\[[^\\]]*]|\\([^)]*\\)")
        private val separators = Regex("[^a-z0-9]+")

        /**
         * A channel name or title as lowercase words: country prefix, feed tags ([BK], (East))
         * and quality tags (HD, 4K) dropped. Called for every channel and guide title, so the
         * patterns are compiled once.
         */
        internal fun tokenize(s: String): List<String> {
            var t = s.lowercase()
            t = t.replace(feedTags, " ")
            leadingCode.find(t)?.let { m ->
                if (m.groupValues[1] in countryCodes) t = t.substring(m.range.last + 1)
            }
            return t.split(separators)
                .filter { it.isNotEmpty() && it !in qualityTags }
        }
    }
}
