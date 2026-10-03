package com.livewire.tv.feature.epg.domain

import java.util.concurrent.TimeUnit

/**
 * How a Guide programme cell should be drawn once catch-up is in play (Phase C). A cell is only
 * "past" when it has already ended; a future or on-air cell is [NORMAL]. Past cells split by
 * whether the channel offers an archive:
 *
 * - [ARCHIVED_PAST]: a past programme on a channel with an archive — eligible for the ⟲ badge
 *   and playable from its start (catch-up).
 * - [DIMMED_PAST]: a past programme on a channel with NO archive — dimmed and not playable.
 */
enum class GuidePastCellStyle { NORMAL, ARCHIVED_PAST, DIMMED_PAST }

/**
 * Pure styling + windowing logic for the catch-up Guide, kept out of Compose so the badge/dim
 * decision and the past-window bounds are unit-tested without an emulator (brief step 4).
 */
object CatchupGuide {

    /** Inline ⟲ badges are dropped on cells narrower than this, so they never crowd a title. */
    const val MIN_BADGE_MINUTES = 40

    /** The shortest past lead when any channel has an archive. */
    val DEFAULT_PAST_LEAD_MINUTES = 120

    /** The furthest back the Guide scrolls, however long a provider's archive is. */
    const val MAX_PAST_DAYS = 2

    /**
     * How far back the Guide opens its window: the longest archive among the channels
     * ([maxArchiveDays]), capped at [MAX_PAST_DAYS], and never less than
     * [DEFAULT_PAST_LEAD_MINUTES]. Providers archive a programme only after it has finished
     * (often ~1.5 h later), so a 2 h lead alone would show almost nothing playable.
     */
    fun pastLeadMinutes(maxArchiveDays: Int): Int =
        maxOf(DEFAULT_PAST_LEAD_MINUTES, maxArchiveDays.coerceIn(0, MAX_PAST_DAYS) * 24 * 60)

    /**
     * The style for a cell showing [programmeStopMs] on a channel whose archive flag is
     * [channelHasArchive], evaluated at [nowMs]. A programme still airing or in the future is
     * [NORMAL]; a past one is [ARCHIVED_PAST] on an archive channel, else [DIMMED_PAST].
     */
    fun cellStyle(programmeStopMs: Long, channelHasArchive: Boolean, nowMs: Long): GuidePastCellStyle = when {
        programmeStopMs > nowMs -> GuidePastCellStyle.NORMAL
        channelHasArchive -> GuidePastCellStyle.ARCHIVED_PAST
        else -> GuidePastCellStyle.DIMMED_PAST
    }

    /**
     * Whether to draw the inline ⟲ archive badge on a past cell: only an [ARCHIVED_PAST] cell at
     * least [MIN_BADGE_MINUTES] wide. The channel-column ⟲ already signals the channel has an
     * archive, so a too-narrow cell dropping its inline badge still reads correctly.
     */
    fun showArchiveBadge(style: GuidePastCellStyle, cellMinutes: Int): Boolean =
        style == GuidePastCellStyle.ARCHIVED_PAST && cellMinutes >= MIN_BADGE_MINUTES

    /** A past cell on a no-archive channel is dimmed. */
    fun isDimmed(style: GuidePastCellStyle): Boolean = style == GuidePastCellStyle.DIMMED_PAST

    /**
     * True when a past programme on an archive channel can be played from its start as catch-up:
     * it has ended and the provider flags it archived ([EpgProgramme] does not carry the flag, so
     * callers pass [hasArchiveFlag] from the on-demand past-listing fetch; for an EPG programme
     * with no past-listing data, [hasArchiveFlag] defaults to the channel's archive flag).
     */
    fun isPlayableArchive(programmeStopMs: Long, hasArchiveFlag: Boolean, nowMs: Long): Boolean =
        programmeStopMs <= nowMs && hasArchiveFlag

    /**
     * True when a programme has ended and started within the channel's [archiveDays] archive
     * window, so the provider's timeshift endpoint can serve it. Used alongside the per-listing
     * `has_archive` flag, which lags the real archive by hours. 0 archive days -> never.
     */
    fun inArchiveWindow(startMs: Long, stopMs: Long, archiveDays: Int, nowMs: Long): Boolean =
        archiveDays > 0 && stopMs <= nowMs && startMs >= nowMs - archiveDays * DAY_MS

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * The Guide window start when catch-up past-scroll is enabled: [leadMinutes] before now,
     * snapped down to the previous half-hour so axis ticks stay on :00/:30. Mirrors the shipped
     * [guideWindowStart] but with a larger lead so past programmes are visible on open.
     */
    fun pastWindowStart(nowMs: Long, leadMinutes: Int = DEFAULT_PAST_LEAD_MINUTES): Long {
        val halfHour = TimeUnit.MINUTES.toMillis(30)
        val lead = TimeUnit.MINUTES.toMillis(leadMinutes.toLong())
        return ((nowMs - lead) / halfHour) * halfHour
    }

    /**
     * The earliest instant a channel's archive reaches, [archiveDays] back from [nowMs] (0 when
     * the channel has no archive). Past listings are only fetched/offered within this bound.
     */
    fun archiveWindowStart(nowMs: Long, archiveDays: Int): Long =
        nowMs - archiveDays.toLong() * 24 * 60 * 60 * 1000
}
