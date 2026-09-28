package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.SportsGame

enum class SearchResultKind { CHANNEL, PROGRAMME, GAME }

data class SearchResult(
    val kind: SearchResultKind,
    val title: String,
    val score: Double,
    val subtitle: String? = null,
    val channel: LiveChannel? = null,
    val programme: EpgProgramme? = null,
    val game: SportsGame? = null,
)

/**
 * Pure ranking over already-loaded data (channels, EPG programmes, sports games).
 * No IO — the ViewModel supplies the lists. Kotlin port of the Flutter SearchIndex.
 * Relevance: exact > prefix > word-boundary > substring; title beats description.
 */
class SearchIndex(
    private val channels: List<LiveChannel> = emptyList(),
    private val programmes: List<EpgProgramme> = emptyList(),
    private val games: List<SportsGame> = emptyList(),
) {
    fun search(query: String, limit: Int = 60): List<SearchResult> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()

        for (c in channels) {
            // Providers pad their channel lists with separator rows ("##### FOX ##### ",
            // "===== SPORTS ====="), which are not playable channels — never surface them.
            if (isSeparatorName(c.name)) continue
            val s = channelScore(c.name, q, query)
            if (s > 0) results.add(
                SearchResult(SearchResultKind.CHANNEL, c.name, s, "Live channel", channel = c),
            )
        }
        for (p in programmes) {
            val titleScore = score(p.title, q)
            val descScore = p.description?.let { score(it, q) * 0.4 } ?: 0.0
            val s = maxOf(titleScore, descScore)
            if (s > 0) results.add(
                SearchResult(SearchResultKind.PROGRAMME, p.title, s * 0.9, "On air", programme = p),
            )
        }
        for (g in games) {
            val hay = "${g.away.name} ${g.away.abbreviation} ${g.home.name} ${g.home.abbreviation} ${g.leagueId}"
            val s = score(hay, q)
            if (s > 0) results.add(
                SearchResult(
                    SearchResultKind.GAME,
                    "${g.away.abbreviation} @ ${g.home.abbreviation}",
                    s * 0.85,
                    g.leagueId.uppercase(),
                    game = g,
                ),
            )
        }

        results.sortByDescending { it.score }
        // Cap each kind separately: a broad query like "fox" matches dozens of channels,
        // and one shared cap would let them crowd the guide and sports sections out.
        // Providers also list the same channel name twice; keep the higher-ranked copy.
        val seenChannelNames = HashSet<String>()
        val perKind = HashMap<SearchResultKind, Int>()
        val capped = results.filter { r ->
            if (r.kind == SearchResultKind.CHANNEL && !seenChannelNames.add(normalize(r.title))) {
                return@filter false
            }
            val n = perKind.getOrDefault(r.kind, 0)
            if (n >= perKindLimit(r.kind, limit)) return@filter false
            perKind[r.kind] = n + 1
            true
        }
        return capped
    }

    private fun perKindLimit(kind: SearchResultKind, total: Int): Int = when (kind) {
        SearchResultKind.CHANNEL -> minOf(total, CHANNEL_LIMIT)
        SearchResultKind.PROGRAMME -> minOf(total, PROGRAMME_LIMIT)
        SearchResultKind.GAME -> minOf(total, GAME_LIMIT)
    }

    companion object {
        const val CHANNEL_LIMIT = 20
        const val PROGRAMME_LIMIT = 20
        const val GAME_LIMIT = 10

        /** exact > prefix > word-boundary > substring; 0 = no match. */
        fun score(candidate: String, normalizedQuery: String): Double {
            val c = normalize(candidate)
            if (c.isEmpty()) return 0.0
            if (c == normalizedQuery) return 1.0
            if (c.startsWith(normalizedQuery)) return 0.85
            if (c.split(" ").any { it.startsWith(normalizedQuery) }) return 0.7
            if (c.contains(normalizedQuery)) return 0.5
            return 0.0
        }

        /**
         * Channel relevance for a network-style query ("fox"). Ranks the way the Sports
         * channel picker ranks candidates for a broadcast network (see
         * [SportsRepository.matchChannels]), so that for "fox":
         *  - the exact network ("US - FOX HD") ranks highest;
         *  - a local affiliate carrying a number ("FOX 26 Houston") ranks next;
         *  - a same-brand spin-off ("FOX NEWS", "FOX SPORTS 1") sinks below both.
         *
         * Built on the same tokenization the picker uses (country/quality/feed tags
         * dropped) so the two screens agree. Falls back to the generic [score] when the
         * query is not a clean token match, so multi-word or substring queries still work.
         */
        fun channelScore(channelName: String, normalizedQuery: String, rawQuery: String): Double {
            val base = score(channelName, normalizedQuery)
            if (base <= 0.0) return 0.0

            val queryTokens = SportsRepository.tokenize(rawQuery)
            val nameTokens = SportsRepository.tokenize(channelName)
            if (queryTokens.isEmpty() || nameTokens.isEmpty()) return base

            val at = indexOfSublist(nameTokens, queryTokens)
            if (at < 0) return base // query not a contiguous token run; keep the generic score

            val extra = nameTokens.filterIndexed { i, _ -> i < at || i >= at + queryTokens.size }
            // Tier the score so exact > affiliate(number) > spin-off, all above a plain
            // substring hit. Kept within (0,1] and above the generic tiers where it wins.
            return when {
                extra.isEmpty() -> 1.0 // exact network name (ignoring country/quality tags)
                extra.any { it in SPIN_OFF_WORDS && it !in queryTokens } -> 0.72 // FOX NEWS / FOX SPORTS
                extra.any { tok -> tok.all(Char::isDigit) } -> 0.9 // local affiliate: FOX 26
                else -> 0.8
            }
        }

        /**
         * True when [name] is a separator/header row rather than a playable channel.
         * Providers pad their lists with dividers like "##### FOX #####",
         * "===== SPORTS =====", "#####" or "____". Two signals catch these without
         * touching real names ("US - FOX HD", "FOX 26 Houston", "US|NBC CHICAGO"):
         *  - the name starts or ends with a run of 2+ marker chars ('#', '=', '*', '_'),
         *    which frames a divider even when it wraps a label; or
         *  - the name has no letter or digit at all.
         */
        fun isSeparatorName(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return true
            if (trimmed.none { it.isLetterOrDigit() }) return true
            return EDGE_MARKER_RUN.containsMatchIn(trimmed)
        }

        private fun indexOfSublist(list: List<String>, sub: List<String>): Int {
            if (sub.isEmpty() || sub.size > list.size) return -1
            for (i in 0..list.size - sub.size) {
                if (list.subList(i, i + sub.size) == sub) return i
            }
            return -1
        }

        /** Brand spin-offs that share a name but do not carry the main feed (mirror of the picker's list). */
        private val SPIN_OFF_WORDS = setOf(
            "news", "weather", "business", "deportes", "espanol", "kids", "life",
            "movies", "classic", "comedy", "reality", "soul", "sports", "sport", "now",
        )

        /** A run of 2+ divider chars at the start or end of a name marks a separator row. */
        private val EDGE_MARKER_RUN = Regex("^[#=*_]{2,}|[#=*_]{2,}$")

        /**
         * Provider names use separators like "US - NBC HD ◉" or "US|NBC", so anything
         * that is not a letter or digit counts as a word break.
         */
        fun normalize(s: String): String =
            s.lowercase().replace(NON_WORD, " ").trim()

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
