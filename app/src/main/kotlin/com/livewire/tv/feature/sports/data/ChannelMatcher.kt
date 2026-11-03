package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.GAME_LISTING
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.GUIDE_LISTING
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.gameWords
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.indexOfSublist
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.namesBothTeams
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.teamNames
import com.livewire.tv.feature.sports.data.SportsRepository.Companion.tokenize
import com.livewire.tv.feature.sports.domain.SportsGame
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * The user's channels and guide, prepared once for matching games to channels.
 *
 * Building it splits every channel name and guide title into words and indexes them by word.
 * [match] then scores only the channels a game could be on: those whose name contains a word
 * of the home team or of a broadcast network, those whose name squashes into a network's
 * ("ESPN2" vs "ESPN 2"), and those whose guide lists both teams while the game is on. With
 * thousands of channels and a hundred thousand listings, that is a few dozen channels per
 * game instead of all of them. Build it off the main thread.
 *
 * Each candidate is scored as before:
 *  - the exact network ("FOX HD" for FOX) ranks highest;
 *  - a local affiliate ("FOX 5 New York") ranks next;
 *  - a spin-off of the same brand ("FOX News", "FOX Weather", "FOX Sports 1")
 *    still matches but sinks to the bottom, since it does not carry the game;
 *  - a squashed-name containment ("ESPN2" vs "ESPN 2") is the last resort.
 * A channel whose name lists both teams (a per-event channel) ranks above all of these,
 * and a channel whose guide listing names both teams while the game is on ranks above that.
 * A network match whose guide shows a different programme then sinks.
 * Deduped by channel, keeping its best score. Ties keep provider order.
 * Channels with the same name (providers often list a backup stream under an
 * identical name) appear once, as the best-ranked of them, since the picker
 * would otherwise show two rows the user cannot tell apart.
 */
class ChannelMatcher(
    private val channels: List<LiveChannel>,
    private val guide: Map<String, List<EpgProgramme>> = emptyMap(),
) {
    private val names: Array<List<String>> = Array(channels.size) { tokenize(channels[it].name) }
    private val squashed: Array<String> = Array(channels.size) { names[it].joinToString("") }

    /** Channel positions by every word in their names. */
    private val byWord: Map<String, IntArray> = buildMap<String, MutableList<Int>> {
        names.forEachIndexed { i, words -> words.toSet().forEach { getOrPut(it) { mutableListOf() }.add(i) } }
    }.mapValues { it.value.toIntArray() }

    private val byGuideId: Map<String, IntArray> =
        channels.withIndex().filter { it.value.epgChannelId != null }
            .groupBy({ it.value.epgChannelId!! }, { it.index })
            .mapValues { it.value.toIntArray() }

    /** A guide listing and the id of the guide channel it belongs to. */
    private class Listing(val guideId: String, val programme: EpgProgramme)

    /**
     * Listings by every word of their title, and of their description when the title looks like
     * a game (the only case where a description can confirm a channel).
     */
    private val listingsByWord: Map<String, List<Listing>> = buildMap<String, MutableList<Listing>> {
        for ((id, programmes) in guide) for (p in programmes) {
            val title = tokenize(p.title)
            val words = title.toMutableSet()
            if (title.any { it in gameWords }) p.description?.let { words += tokenize(it) }
            val listing = Listing(id, p)
            words.forEach { getOrPut(it) { mutableListOf() }.add(listing) }
        }
    }

    /** The channels that appear to carry [game], best match first. */
    fun match(game: SportsGame): List<ChannelMatch> {
        val home = teamNames(game.home)
        val away = teamNames(game.away)
        // Each network as listed ("ESPN2") with its words, skipping names with no words.
        val listed = game.broadcastNetworks.mapNotNull { n -> tokenize(n).takeIf { it.isNotEmpty() }?.let { n to it } }
        val networks = listed.map { it.second }
        // Providers reuse one guide id across related channels ("espn.us" on ESPN,
        // ESPN 1, ESPN ACC Network and ESPN SEC Network), so a shared id's listing
        // cannot say which of them carries the game.
        val from = game.startTimeMs - PREGAME_MS
        val to = game.startTimeMs + GAME_LENGTH_MS

        val candidates = TreeSet<Int>()
        for (name in home) byWord[name.first()]?.forEach { candidates += it }
        for (net in networks) {
            byWord[net.first()]?.forEach { candidates += it }
            val n = net.joinToString("")
            if (n.length >= MIN_SQUASHED) squashed.forEachIndexed { i, c ->
                if (c.length >= MIN_SQUASHED && (c.contains(n) || n.contains(c))) candidates += i
            }
        }
        if (guide.isNotEmpty()) for (name in home) listingsByWord[name.first()]?.forEach { l ->
            val p = l.programme
            if (p.startMs < to && p.stopMs > from) byGuideId[l.guideId]?.forEach { candidates += it }
        }

        data class Scored(val match: ChannelMatch, val score: Int, val order: Int)
        val best = LinkedHashMap<String, Scored>()
        for (order in candidates) {
            val ch = channels[order]
            val chTokens = names[order]
            if (chTokens.isEmpty()) continue
            val bestNetwork = networks.mapNotNull { score(it, chTokens) }.maxOrNull()
            // What the guide says this channel airs while the game is on. A listing
            // naming both teams ("New York Rangers at Boston Bruins") confirms the
            // channel carries the game, which beats every guess from its name.
            val listing = guideListing(ch, from, to, home, away)
            val sharedGuideId = (byGuideId[ch.epgChannelId]?.size ?: 0) > 1
            // A shared id confirms only the channel named after the network itself,
            // optionally with a feed number ("ESPN HD", "ESPN 1 HD"), never a
            // sibling that borrows its guide ("ESPN ACC Network").
            val confirmed = listing == GuideListing.THIS_GAME &&
                (!sharedGuideId || networks.any { plainNetworkName(it, chTokens) })
            if (confirmed) {
                // Network closeness orders the confirmed channels among themselves.
                best[ch.streamId] = Scored(ChannelMatch(ch, GUIDE_LISTING), GUIDE_SCORE + (bestNetwork ?: 0), order)
                continue
            }
            // Providers run per-event channels named after the game itself
            // ("NHL GAME 03 : NEW YORK RANGERS @ BOSTON BRUINS", "ESPN PLUS 61 : NHL:
            // NYR @ BOS", "PEACOCK 03 : RED SOX @ YANKEES"). Naming both teams beats any
            // network match: it is the stream carrying this game, and it is often the
            // only one when the network is a streaming service (ESPN+, Prime Video).
            if (namesBothTeams(chTokens, home, away)) {
                best[ch.streamId] = Scored(ChannelMatch(ch, GAME_LISTING), EVENT_SCORE, order)
                continue
            }
            for ((network, netTokens) in listed) {
                var score = score(netTokens, chTokens) ?: continue
                // The network matches, but the guide shows something else then.
                // Kept (guides can be wrong) but ranked below the likely channels.
                if (listing == GuideListing.OTHER_PROGRAMME) score -= GUIDE_MISMATCH_PENALTY
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

    private enum class GuideListing { THIS_GAME, OTHER_PROGRAMME, UNKNOWN }

    /**
     * Classify [ch]'s guide listings between [from] and [to]. THIS_GAME when a listing's title
     * names both teams, or its description does and its title looks like a live game
     * ("NHL Hockey"). OTHER_PROGRAMME when the guide has listings then but none looks like a
     * game. UNKNOWN when there is no guide data, or a listing looks like a game without naming
     * these teams (it may still be this one).
     */
    private fun guideListing(
        ch: LiveChannel,
        from: Long,
        to: Long,
        home: List<List<String>>,
        away: List<List<String>>,
    ): GuideListing {
        val id = ch.epgChannelId ?: return GuideListing.UNKNOWN
        val programmes = guide[id] ?: return GuideListing.UNKNOWN
        val during = programmes.filter { it.startMs < to && it.stopMs > from }
        if (during.isEmpty()) return GuideListing.UNKNOWN
        var sawGameLike = false
        for (p in during) {
            val title = tokenize(p.title)
            if (namesBothTeams(title, home, away)) return GuideListing.THIS_GAME
            if (title.any { it in gameWords }) {
                sawGameLike = true
                p.description?.let { if (namesBothTeams(tokenize(it), home, away)) return GuideListing.THIS_GAME }
            }
        }
        return if (sawGameLike) GuideListing.UNKNOWN else GuideListing.OTHER_PROGRAMME
    }

    private companion object {
        const val MIN_SQUASHED = 3

        /** Above every network score (an exact network is 100). */
        const val EVENT_SCORE = 200

        /** The guide confirms the channel airs this game: above a per-event stream too,
         *  since it is the network's own broadcast of it. */
        const val GUIDE_SCORE = 250

        /** Moves a network match whose guide shows another programme below the ones
         *  whose guide agrees or says nothing, without dropping it. */
        const val GUIDE_MISMATCH_PENALTY = 50

        /** How long a game is assumed to run, for finding its guide listings. */
        val GAME_LENGTH_MS = TimeUnit.HOURS.toMillis(3)
        val PREGAME_MS = TimeUnit.MINUTES.toMillis(15)

        private val spinOffWords = setOf(
            "news", "weather", "business", "deportes", "espanol", "kids", "life",
            "movies", "classic", "comedy", "reality", "soul", "sports", "sport", "now",
        )

        /** Score of [chan] for network [net], or null if it does not match at all. */
        fun score(net: List<String>, chan: List<String>): Int? {
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

        /** [chan] is [net]'s own name, optionally plus a feed number ("ESPN 1"). */
        fun plainNetworkName(net: List<String>, chan: List<String>): Boolean {
            val at = indexOfSublist(chan, net)
            if (at < 0) return false
            return chan.filterIndexed { i, _ -> i < at || i >= at + net.size }.all { t -> t.all(Char::isDigit) }
        }
    }
}
