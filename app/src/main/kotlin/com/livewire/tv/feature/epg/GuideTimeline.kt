package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import java.util.concurrent.TimeUnit

/**
 * Pure timeline maths for the guide grid (design system section 9.4, 7). No Compose/Android
 * here so it is unit-testable. All times are epoch millis; positions are in whole minutes
 * from the window start, which the screen multiplies by pixels-per-minute.
 */

/**
 * Offset of the red now-line from the window start, in minutes. Clamped to the window so a
 * now that has drifted past the visible span still draws at the right edge rather than off it.
 * Returns null when now is before the window start (the line should not be drawn).
 */
fun nowLineMinutes(nowMs: Long, windowStartMs: Long, windowSpanMs: Long): Int? {
    if (nowMs < windowStartMs) return null
    val spanMin = TimeUnit.MILLISECONDS.toMinutes(windowSpanMs).toInt()
    val offset = TimeUnit.MILLISECONDS.toMinutes(nowMs - windowStartMs).toInt()
    return offset.coerceIn(0, spanMin)
}

/**
 * Which cell of a row should take focus when the guide first opens: the index into
 * [laneCells]' output whose programme is airing at [nowMs], or 0 when this row has no
 * programme on now (an empty row, or one whose visible programmes are all past/future).
 *
 * Kept pure and cell-list-based so it matches exactly what the screen renders: the same
 * [laneCells] a row draws is passed here, so index N always addresses the Nth focusable
 * [com.livewire.tv.feature.epg] cell. The details band uses the same on-now test, so the
 * ring and the band agree on open.
 */
internal fun initialFocusCellIndex(cells: List<LaneCell>, nowMs: Long): Int {
    val onNow = cells.indexOfFirst { it.programme.airsAt(nowMs) }
    return if (onNow >= 0) onNow else 0
}

/**
 * Which cell of a row Up/Down should land on: the one airing at [anchorMs] (the time the
 * user is browsing), else the cell nearest to it in time. A row with no cells has a single
 * empty cell, index 0. Up/Down never change the anchor, so passing over an empty row or a
 * long programme does not drag focus to a different time slot.
 */
internal fun cellIndexForAnchor(cells: List<LaneCell>, anchorMs: Long): Int {
    if (cells.isEmpty()) return 0
    val containing = cells.indexOfFirst { anchorMs >= it.programme.startMs && anchorMs < it.programme.stopMs }
    if (containing >= 0) return containing
    return cells.indices.minBy { i ->
        val p = cells[i].programme
        when {
            anchorMs < p.startMs -> p.startMs - anchorMs
            else -> anchorMs - p.stopMs + 1
        }
    }
}

/**
 * The anchor a Left/Right move sets when it lands on [programme]: now while it is on air
 * (so browsing the live column keeps following the live programme), otherwise its start,
 * clipped to the window so a programme that began before the window anchors at the edge.
 * Null (an empty row's cell) means "keep the current anchor".
 */
internal fun anchorForCell(programme: EpgProgramme?, nowMs: Long, windowStartMs: Long): Long? = when {
    programme == null -> null
    programme.airsAt(nowMs) -> nowMs
    else -> maxOf(programme.startMs, windowStartMs)
}

/**
 * The guide window aligned so rows start NEAR now, not an hour before (brief). We snap the
 * start down to the previous half-hour and keep a small [leadMinutes] lead-in so the now-line
 * sits just inside the left edge with a little context before it, rather than dead-centre.
 */
fun guideWindowStart(nowMs: Long, leadMinutes: Int = 30): Long {
    val halfHour = TimeUnit.MINUTES.toMillis(30)
    val flooredNow = (nowMs / halfHour) * halfHour
    val lead = TimeUnit.MINUTES.toMillis(leadMinutes.toLong())
    // Snap the lead-in start down to the previous half-hour too, so axis ticks stay on :00/:30.
    val startCandidate = flooredNow - lead
    return (startCandidate / halfHour) * halfHour
}
