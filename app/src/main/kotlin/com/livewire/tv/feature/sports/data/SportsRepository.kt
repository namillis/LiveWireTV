package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsProvider
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.SportsStandings
import com.livewire.tv.feature.sports.domain.TeamSide
import javax.inject.Inject
import javax.inject.Singleton

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
    suspend fun standings(leagueId: String): SportsStandings = provider.standings(leagueId)

    companion object {
        /**
         * Fuse a game's broadcast networks against the user's live channels, best match
         * first. IPTV names rarely equal a broadcast name ("US - FOX 5 NEW YORK HD"), so
         * names are tokenized (country prefix, feed tags and quality tags dropped) and
         * each candidate is scored:
         *  - the exact network ("FOX HD" for FOX) ranks highest;
         *  - a local affiliate ("FOX 5 New York") ranks next;
         *  - a spin-off of the same brand ("FOX News", "FOX Weather", "FOX Sports 1")
         *    still matches but sinks to the bottom, since it does not carry the game;
         *  - a squashed-name containment ("ESPN2" vs "ESPN 2") is the last resort.
         * A channel whose name lists both teams (a per-event channel) ranks above all of these.
         * Deduped by channel, keeping its best score. Ties keep provider order.
         * Channels with the same name (providers often list a backup stream under an
         * identical name) appear once, as the best-ranked of them, since the picker
         * would otherwise show two rows the user cannot tell apart.
         */
        fun matchChannels(game: SportsGame, channels: List<LiveChannel>): List<ChannelMatch> {
            data class Scored(val match: ChannelMatch, val score: Int, val order: Int)
            val best = LinkedHashMap<String, Scored>()
            val homeNames = teamNames(game.home)
            val awayNames = teamNames(game.away)
            channels.forEachIndexed { order, ch ->
                val chTokens = tokenize(ch.name)
                if (chTokens.isEmpty()) return@forEachIndexed
                // Providers run per-event channels named after the game itself
                // ("NHL GAME 03 : NEW YORK RANGERS @ BOSTON BRUINS", "ESPN PLUS 61 : NHL:
                // NYR @ BOS", "PEACOCK 03 : RED SOX @ YANKEES"). Naming both teams beats any
                // network match: it is the stream carrying this game, and it is often the
                // only one when the network is a streaming service (ESPN+, Prime Video).
                if (homeNames.any { indexOfSublist(chTokens, it) >= 0 } &&
                    awayNames.any { indexOfSublist(chTokens, it) >= 0 }
                ) {
                    best[ch.streamId] = Scored(ChannelMatch(ch, GAME_LISTING), EVENT_SCORE, order)
                    return@forEachIndexed
                }
                for (network in game.broadcastNetworks) {
                    val netTokens = tokenize(network)
                    if (netTokens.isEmpty()) continue
                    val score = score(netTokens, chTokens) ?: continue
                    val prev = best[ch.streamId]
                    if (prev == null || score > prev.score) {
                        best[ch.streamId] = Scored(ChannelMatch(ch, network), score, order)
                    }
                }
            }
            return best.values
                .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
                .map { it.match }
                .distinctBy { it.channel.name.trim().lowercase() }
        }

        /** Score of [chan] for network [net], or null if it does not match at all. */
        private fun score(net: List<String>, chan: List<String>): Int? {
            val at = indexOfSublist(chan, net)
            if (at < 0) {
                // Fallback: squashed containment either way ("espn2" vs ["espn","2"]).
                // Both sides need 3+ characters: a one-letter name like "E!" would
                // otherwise match any network containing that letter ("Prime Video").
                val n = net.joinToString(""); val c = chan.joinToString("")
                if (n.length < MIN_SQUASHED || c.length < MIN_SQUASHED) return null
                return if (c.contains(n) || n.contains(c)) 10 else null
            }
            val extra = chan.filterIndexed { i, _ -> i < at || i >= at + net.size }
            if (extra.isEmpty()) return 100
            var score = 60 - 3 * extra.size
            if (at == 0) score += 5
            // Spin-off brands: same name, different channel ("FOX News", "ESPN Deportes").
            if (extra.any { it in spinOffWords && it !in net }) score -= 40
            // Local affiliates carry a channel number ("FOX 5", "WNYW FOX 5").
            else if (extra.any { it.all(Char::isDigit) }) score += 15
            return score
        }

        private fun indexOfSublist(list: List<String>, sub: List<String>): Int {
            if (sub.size > list.size) return -1
            for (i in 0..list.size - sub.size) {
                if (list.subList(i, i + sub.size) == sub) return i
            }
            return -1
        }

        private const val MIN_SQUASHED = 3

        /** Above every network score (an exact network is 100). */
        private const val EVENT_SCORE = 200

        /** What the picker shows as the reason for an event-channel match ("MATCHED THIS GAME"). */
        const val GAME_LISTING = "this game"

        /** Every way a listing may name [team]: full name, nickname, abbreviation. */
        private fun teamNames(team: TeamSide): List<List<String>> =
            listOf(team.name, team.shortName, team.abbreviation)
                .map(::tokenize)
                .filter { it.isNotEmpty() }
                .distinct()

        private val qualityTags = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "raw")

        private val spinOffWords = setOf(
            "news", "weather", "business", "deportes", "espanol", "kids", "life",
            "movies", "classic", "comedy", "reality", "soul", "sports", "sport", "now",
        )

        /** Country codes providers prefix names with ("US - ", "UK| ", "CA:"). */
        private val countryCodes = setOf(
            "us", "usa", "uk", "ca", "au", "nz", "ie", "de", "fr", "es", "it", "nl", "pt",
            "br", "mx", "ar", "in", "pk", "tr", "pl", "am", "latam",
        )
        private val leadingCode = Regex("^\\s*([a-z]{2,5})\\s*[-|:]\\s*")

        internal fun tokenize(s: String): List<String> {
            var t = s.lowercase()
            t = t.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ") // feed tags: [BK], (East)
            leadingCode.find(t)?.let { m ->
                if (m.groupValues[1] in countryCodes) t = t.substring(m.range.last + 1)
            }
            return t.split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() && it !in qualityTags }
        }
    }
}
