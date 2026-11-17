package com.livewire.tv.feature.sports

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.sports.data.ChannelMatch
import com.livewire.tv.feature.sports.data.SportsRepository
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague

/** Where a game will play from, as the Sports list shows it at the end of the row. */
sealed interface GameSource {
    /** Still looking: the channel list or the guide has not loaded yet. */
    data object Finding : GameSource

    /** The pool's Live Now channel for this game: the pool picks the stream and fails over. */
    data class LiveNow(val channel: LiveChannel) : GameSource

    /** The best of the user's own channels that appear to carry it. */
    data class Channel(val match: ChannelMatch) : GameSource

    /** Nothing found once the channels and guide were searched. */
    data object None : GameSource
}

/**
 * One way to watch a game on its page. [listing] is the guide programme that confirmed a guide
 * match, so the row can mark it NOW while it airs.
 */
data class SourceOption(val channel: LiveChannel, val reason: String, val liveNow: Boolean, val listing: EpgProgramme? = null)

/** Watch from start: catch-up on [channel] over [startMs, endMs], titled [programme]. */
data class FromStart(val channel: LiveChannel, val startMs: Long, val endMs: Long, val programme: String)

/** The list's sections, in screen order. */
enum class SportsSection(val label: String) { ON_NOW("On now"), COMING_UP("Coming up"), FINISHED("Finished") }

/** A league chip: the league and how many games it has. */
data class LeagueChip(val id: String?, val name: String, val count: Int)

/**
 * Pure rules for the Sports list (one list across every league). No Android, so every case runs
 * on the JVM.
 */
object SportsList {
    fun sectionOf(game: SportsGame): SportsSection = when (game.status.state) {
        GameState.IN_PROGRESS -> SportsSection.ON_NOW
        GameState.FINAL -> SportsSection.FINISHED
        GameState.PRE, GameState.UNKNOWN -> SportsSection.COMING_UP
    }

    /**
     * [games] in [filter]'s league (every league when null), grouped into sections. Empty sections
     * are left out. On now keeps the league order and then start time, so a league's live games
     * stay together. Coming up runs soonest first. Finished runs most recent first.
     */
    fun arrange(games: List<SportsGame>, leagues: List<SportsLeague>, filter: String?): List<Pair<SportsSection, List<SportsGame>>> {
        val rank = leagues.withIndex().associate { it.value.id to it.index }
        val shown = games.filter { filter == null || it.leagueId == filter }
        val bySection = shown.groupBy(::sectionOf)
        return SportsSection.entries.mapNotNull { section ->
            val list = bySection[section].orEmpty()
            val sorted = when (section) {
                SportsSection.ON_NOW -> list.sortedWith(compareBy({ rank[it.leagueId] ?: Int.MAX_VALUE }, { it.startTimeMs }))
                SportsSection.COMING_UP -> list.sortedWith(compareBy({ it.startTimeMs }, { rank[it.leagueId] ?: Int.MAX_VALUE }))
                SportsSection.FINISHED -> list.sortedByDescending { it.startTimeMs }
            }
            if (sorted.isEmpty()) null else section to sorted
        }
    }

    /** "All" first, then each league that has games, in league order. */
    fun chips(games: List<SportsGame>, leagues: List<SportsLeague>): List<LeagueChip> {
        val counts = games.groupingBy { it.leagueId }.eachCount()
        return listOf(LeagueChip(null, "All", games.size)) +
            leagues.mapNotNull { l -> counts[l.id]?.let { LeagueChip(l.id, l.name, it) } }
    }

    /**
     * Where [game] plays from. [liveNow] is the pool's Live Now channel for it, if any; [matches]
     * are its channel matches, or null while the channel list is still loading; [settled] is true
     * once the guide has loaded or failed, so an empty result is final. Finished games have none.
     */
    fun source(game: SportsGame, liveNow: LiveChannel?, matches: List<ChannelMatch>?, settled: Boolean): GameSource? = when {
        game.status.state == GameState.FINAL -> null
        liveNow != null -> GameSource.LiveNow(liveNow)
        matches == null -> GameSource.Finding
        matches.isNotEmpty() -> GameSource.Channel(matches.first())
        settled -> GameSource.None
        else -> GameSource.Finding
    }

    /** The Other channels panel: the Live Now channel first, then the matches, each channel once. */
    fun options(liveNow: LiveChannel?, matches: List<ChannelMatch>): List<SourceOption> {
        val out = mutableListOf<SourceOption>()
        liveNow?.let { out += SourceOption(it, POOL_REASON, liveNow = true) }
        for (m in matches) {
            if (out.any { it.channel.streamId == m.channel.streamId }) continue
            out += SourceOption(m.channel, reason(m), liveNow = false, listing = m.listing)
        }
        return out
    }

    /**
     * Why a channel is on the game page, in plain words. A guide match shows the listing itself:
     * "Rangers at Bruins · 7:00 PM–10:00 PM", or "NHL Hockey · 7:00 PM–10:00 PM · Bruins host the
     * Rangers." when only the description names the teams.
     */
    fun reason(m: ChannelMatch, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = when (m.matchedNetwork) {
        SportsRepository.GUIDE_LISTING -> m.listing?.let { p ->
            val out = "${p.title.trim()} · ${clock(p.startMs, zone)}–${clock(p.stopMs, zone)}"
            val desc = p.description?.trim()?.takeIf { it.isNotEmpty() && !m.listingTitleNamesTeams }
            if (desc != null) "$out · $desc" else out
        } ?: "Guide lists this game"
        SportsRepository.GAME_LISTING -> "Named after this game"
        else -> "Matches ${m.matchedNetwork}"
    }

    /** "7:00 PM". */
    fun clock(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))

    /**
     * The channel and window Watch from start plays for [game]: the first of [options] that keeps
     * an archive, from its guide listing's start (else kickoff) to its end (else three hours on).
     * Only while the game is on; null when no channel keeps an archive.
     */
    fun fromStart(game: SportsGame, options: List<SourceOption>): FromStart? {
        if (game.status.state != GameState.IN_PROGRESS) return null
        val o = options.firstOrNull { it.channel.hasArchive } ?: return null
        val start = o.listing?.startMs ?: game.startTimeMs
        val end = o.listing?.stopMs ?: (start + java.util.concurrent.TimeUnit.HOURS.toMillis(3))
        return FromStart(o.channel, start, end, o.listing?.title ?: title(game))
    }

    const val POOL_REASON = "Pool · picks the best stream and switches if it drops"

    /**
     * What the Search screen looks for when nothing was found: one team's name ("New Mexico",
     * "Bears"). Search matches its query as one phrase, so both names together would match
     * nothing; per-event channels and the game itself both contain either name.
     */
    fun searchQuery(game: SportsGame): String = game.away.shortName.ifBlank { game.away.name }

    /** "Bears @ Seahawks": nicknames when ESPN sends them, else full names. */
    fun title(game: SportsGame): String =
        "${game.away.shortName.ifBlank { game.away.name }} @ ${game.home.shortName.ifBlank { game.home.name }}"

    /** A coming game's start: "10:30 PM" today, "Thu 8:15 PM" on another day. */
    fun startLabel(startMs: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val start = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = start.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
        return if (start.toLocalDate() == today) time
        else start.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.US)) + " " + time
    }

    /** The pill's channel label: the channel's name, or "Live Now". */
    fun pillLabel(source: GameSource): String = when (source) {
        is GameSource.LiveNow -> "Live Now"
        is GameSource.Channel -> source.match.channel.name
        GameSource.Finding -> "Finding"
        GameSource.None -> "No stream found"
    }
}
