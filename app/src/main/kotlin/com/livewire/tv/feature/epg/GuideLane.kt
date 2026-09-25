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
