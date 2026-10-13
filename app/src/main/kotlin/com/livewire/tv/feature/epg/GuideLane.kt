package com.livewire.tv.feature.epg

import com.livewire.tv.feature.epg.domain.EpgProgramme
import java.util.concurrent.TimeUnit

/** One programme placed on a guide lane: blank minutes before it, then its visible length. */
internal data class LaneCell(
    val programme: EpgProgramme,
    val gapMinutes: Int,
    val minutes: Int,
)

/** Shortest cell we draw, so a 1-minute filler stays focusable and readable. */
private const val MIN_CELL_MINUTES = 5

/**
 * Lay [programmes] out on a lane that starts at [windowStartMs] and spans [windowSpanMs].
 * Programmes are clipped to the window, overlaps are pushed right, and the total never
 * exceeds the window, so every lane has the same width and rows stay aligned.
 */
internal fun laneCells(
    programmes: List<EpgProgramme>,
    windowStartMs: Long,
    windowSpanMs: Long,
): List<LaneCell> {
    val windowEndMs = windowStartMs + windowSpanMs
    val spanMinutes = TimeUnit.MILLISECONDS.toMinutes(windowSpanMs).toInt()
    var cursor = 0 // minutes from window start already used
    val cells = mutableListOf<LaneCell>()
    for (p in programmes.sortedBy { it.startMs }) {
        if (p.stopMs <= windowStartMs || p.startMs >= windowEndMs) continue
        val start = minutesFrom(windowStartMs, maxOf(p.startMs, windowStartMs))
        val end = minutesFrom(windowStartMs, minOf(p.stopMs, windowEndMs))
        val from = maxOf(start, cursor)
        if (from >= spanMinutes) break
        val length = maxOf(end - from, MIN_CELL_MINUTES).coerceAtMost(spanMinutes - from)
        if (length <= 0) continue
        cells += LaneCell(p, gapMinutes = from - cursor, minutes = length)
        cursor = from + length
    }
    return cells
}

private fun minutesFrom(originMs: Long, t: Long): Int =
    TimeUnit.MILLISECONDS.toMinutes(t - originMs).toInt()

/** Where each of [cells] starts on its lane, in minutes from the window start (after its gap). */
internal fun cellStartMinutes(cells: List<LaneCell>): List<Int> {
    var cursor = 0
    return cells.map { cell ->
        val start = cursor + cell.gapMinutes
        cursor = start + cell.minutes
        start
    }
}

/**
 * How far a cell's text moves right, in pixels, so it stays readable when the cell starts left
 * of the visible lane: by the part of the cell scrolled off screen ([scrollPx] - [cellStartPx]),
 * but never so far that less than [minTextPx] of the cell's [textAreaPx] is left for the text.
 * Past that point the text scrolls off with the cell. Zero for a cell that starts on screen.
 *
 * Without it a cell's title is drawn at the programme's start, off screen, so a programme that
 * began before the visible part of the timeline looks blank. Live Now games run for hours, so
 * their rows hit this most.
 */
internal fun stickyTextShift(cellStartPx: Int, scrollPx: Int, textAreaPx: Int, minTextPx: Int): Int =
    (scrollPx - cellStartPx).coerceIn(0, (textAreaPx - minTextPx).coerceAtLeast(0))
