package com.livewire.tv.feature.search.domain

import com.livewire.tv.feature.epg.domain.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure grouping and label logic behind the restyled Search screen (design system §4, §9;
 * Option 1 "grouped results" mockup). No Android/Compose calls here so it stays
 * unit-testable — the screen renders these strings and lists and supplies `now`/`zone`.
 *
 * A flat, already-ranked [SearchResult] list from [SearchIndex] is split into the three
 * fixed sections the mockup shows, in order: CHANNELS (a card rail), ON TV (guide
 * programme rows) and SPORTS (game rows). Within CHANNELS and SPORTS the [SearchIndex]
 * ranking order is preserved; ON TV drops programmes that have already ended and is
 * ordered now-first, then by start time.
 */

/** The three result sections, in the order the mockup lays them out. */
enum class SearchSection { CHANNELS, ON_TV, SPORTS }

/**
 * A flat result list split by kind, each sublist keeping the incoming (ranked) order.
 * [total] is the number of results across all sections (matches the leading count).
 */
data class GroupedSearch(
    val channels: List<SearchResult> = emptyList(),
    val programmes: List<SearchResult> = emptyList(),
    val games: List<SearchResult> = emptyList(),
) {
    val total: Int get() = channels.size + programmes.size + games.size

    /** True when there is nothing to show (all three sections empty). */
    fun isEmpty(): Boolean = total == 0

    /** Sections that actually have results, in mockup order — the caller renders only these. */
    fun nonEmptySections(): List<SearchSection> = buildList {
        if (channels.isNotEmpty()) add(SearchSection.CHANNELS)
        if (programmes.isNotEmpty()) add(SearchSection.ON_TV)
        if (games.isNotEmpty()) add(SearchSection.SPORTS)
    }
}

object SearchGrouping {

    /**
     * Split a ranked result list into the three fixed sections. Channels and games keep
     * their ranked order; ON TV programmes are filtered to those airing now or starting
     * later (a programme that already ended is dropped) and ordered now-first, then by
     * start time. [now] is epoch millis so the filter/sort are deterministic in tests.
     */
    fun group(results: List<SearchResult>, now: Long = System.currentTimeMillis()): GroupedSearch = GroupedSearch(
        channels = results.filter { it.kind == SearchResultKind.CHANNEL },
        programmes = results
            .filter { it.kind == SearchResultKind.PROGRAMME && it.programme != null && it.programme.stopMs > now }
            .sortedWith(
                // Airing-now first, then earliest upcoming start; a ranked stable sort keeps
                // relevance order within a tie.
                compareByDescending<SearchResult> { it.programme!!.airsAt(now) }
                    .thenBy { it.programme!!.startMs },
            ),
        games = results.filter { it.kind == SearchResultKind.GAME },
    )

    /**
     * The count line under the query field: "12 results · 4 channels · 5 on TV · 3 games".
     * Only non-zero section counts appear; the leading total is always shown (as "result"
     * / "results"). Returns "" when there is nothing to count, so the caller shows none.
     */
    fun countLine(grouped: GroupedSearch): String {
        if (grouped.isEmpty()) return ""
        val parts = mutableListOf("${grouped.total} ${plural(grouped.total, "result", "results")}")
        if (grouped.channels.isNotEmpty()) parts += "${grouped.channels.size} channels"
        if (grouped.programmes.isNotEmpty()) parts += "${grouped.programmes.size} on TV"
        if (grouped.games.isNotEmpty()) parts += "${grouped.games.size} games"
        return parts.joinToString(" · ")
    }

    private fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

    // ── Programme (ON TV) row labels ───────────────────────────────────────────────

    /** True while [programme] is airing at [now] — the one programme case that renders NOW/red. */
    fun isAiring(programme: EpgProgramme, now: Long): Boolean = programme.airsAt(now)

    /**
     * The left pill on a programme row: "NOW" while airing, otherwise the start time
     * ("9:00 PM"). The screen picks red for the NOW case and muted for a start time.
     */
    fun programmePill(programme: EpgProgramme, now: Long, zone: TimeZone = TimeZone.getDefault()): String =
        if (programme.airsAt(now)) "NOW" else formatTime(programme.startMs, zone)

    /**
     * The time-range label on a programme row: "8:00 – 9:00 PM". When both ends share the
     * same meridiem the first one drops it ("8:00 – 9:00 PM"); otherwise both keep it
     * ("11:30 AM – 12:30 PM").
     */
    fun programmeTimeRange(programme: EpgProgramme, zone: TimeZone = TimeZone.getDefault()): String {
        val startMer = meridiem(programme.startMs, zone)
        val endMer = meridiem(programme.stopMs, zone)
        val start = if (startMer == endMer) formatTimeNoMeridiem(programme.startMs, zone)
        else formatTime(programme.startMs, zone)
        val end = formatTime(programme.stopMs, zone)
        return "$start – $end"
    }

    private fun formatTime(ms: Long, zone: TimeZone): String =
        SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }.format(Date(ms))

    private fun formatTimeNoMeridiem(ms: Long, zone: TimeZone): String =
        SimpleDateFormat("h:mm", Locale.US).apply { timeZone = zone }.format(Date(ms))

    private fun meridiem(ms: Long, zone: TimeZone): String =
        SimpleDateFormat("a", Locale.US).apply { timeZone = zone }.format(Date(ms))
}
