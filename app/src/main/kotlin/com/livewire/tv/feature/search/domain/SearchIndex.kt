package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
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
            val s = score(c.name, q)
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
        return if (results.size > limit) results.subList(0, limit) else results
    }

    companion object {
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

        fun normalize(s: String): String =
            s.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}
