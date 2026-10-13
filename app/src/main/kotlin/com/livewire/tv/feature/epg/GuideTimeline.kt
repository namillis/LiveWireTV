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

/**
 * How far a programme lane scrolls to bring a newly focused cell into view, or null to use
 * the platform rule. Positions are pixels in the lane's viewport ([offset] is the cell's
 * leading edge, negative when it starts left of the viewport).
 *
 * On a TV the platform rule puts the focused cell's leading edge 30% into the viewport on
 * every focus change, even when the cell is already in view. That suits Left/Right
 * ([stepping]): the programme's title is at its start. It does not suit Up/Down, which land
 * on the cell airing at the time being browsed: a programme that began earlier dragged the
 * lane back on each row, so scrolling down a category drifted hours into the past, and the
 * empty "No programme information" cell, which spans the whole window, jumped a cold-opened
 * guide to the window start two days back.
 *
 * So for Up/Down (and the guide placing focus itself) a cell that is on screen at all keeps
 * the lane still; for Left/Right only a fully visible cell does. A cell that is entirely off
 * screen always uses the platform rule.
 */
internal fun laneScrollDistance(offset: Float, size: Float, containerSize: Float, stepping: Boolean): Float? {
    val trailing = offset + size
    if (offset >= containerSize || trailing <= 0f) return null
    val fullyVisible = offset >= 0f && trailing <= containerSize
    return if (fullyVisible || !stepping) 0f else null
}

/**
 * Whether a focus change the screen did not request should count as the user moving focus
 * (which stops the guide from placing focus on the now programme itself). Before the
 * programmes load every row is a single empty cell, and focus entering it then comes from
 * the nav drawer handing focus to the screen, not from the user browsing.
 */
internal fun isUserFocusMove(programme: EpgProgramme?, programmesLoaded: Boolean): Boolean =
    programme != null || programmesLoaded

/**
 * The anchor for a Left/Right move from [focused] to the neighbouring cell in [cells], or
 * null when there is no neighbour that way (the row's first or last cell, an empty row, or a
 * programme no longer drawn). The guide moves focus through the anchor, like Up/Down, rather
 * than leaving it to the platform's focus search: that search only looks at what is on
 * screen, so from a long programme whose neighbour is scrolled out of view it jumped to the
 * category column (Left) or did nothing (Right).
 *
 * The anchor is the one a Left/Right landing sets ([anchorForCell]) when that resolves to
 * the neighbour; overlapping listings can make it resolve to the cell before, so the
 * programme's start and then its midpoint are tried next.
 */
internal fun neighbourAnchor(
    cells: List<LaneCell>,
    focused: EpgProgramme,
    forward: Boolean,
    nowMs: Long,
    windowStartMs: Long,
): Long? {
    val index = cells.indexOfFirst { it.programme == focused }
    if (index < 0) return null
    val target = index + if (forward) 1 else -1
    val programme = cells.getOrNull(target)?.programme ?: return null
    val candidates = listOfNotNull(
        anchorForCell(programme, nowMs, windowStartMs),
        maxOf(programme.startMs, windowStartMs),
        programme.startMs + (programme.stopMs - programme.startMs) / 2,
    )
    return candidates.firstOrNull { cellIndexForAnchor(cells, it) == target }
}
