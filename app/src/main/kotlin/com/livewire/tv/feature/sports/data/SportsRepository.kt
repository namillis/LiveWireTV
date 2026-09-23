package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsProvider
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.SportsStandings
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
         * first. Fuzzy on purpose: broadcast names rarely equal an IPTV channel name, so
         * we normalize (lowercase, drop HD/4K tags + non-alphanumerics) and accept a
         * containment match either direction. Deduped by channel. Unit-tested.
         */
        fun matchChannels(game: SportsGame, channels: List<LiveChannel>): List<ChannelMatch> {
            val matches = mutableListOf<ChannelMatch>()
            val seen = mutableSetOf<String>()
            for (network in game.broadcastNetworks) {
                val net = normalize(network)
                if (net.isEmpty()) continue
                for (ch in channels) {
                    if (ch.streamId in seen) continue
                    val name = normalize(ch.name)
                    if (name.isEmpty()) continue
                    if (name.contains(net) || net.contains(name)) {
                        matches.add(ChannelMatch(ch, network))
                        seen.add(ch.streamId)
                    }
                }
            }
            return matches
        }

        private val qualityTags = setOf("hd", "fhd", "uhd", "4k", "sd", "hevc", "h265")

        private fun normalize(s: String): String {
            var t = s.lowercase()
            for (tag in qualityTags) t = t.replace(Regex("\\b$tag\\b"), "")
            return t.replace(Regex("[^a-z0-9]"), "")
        }
    }
}
