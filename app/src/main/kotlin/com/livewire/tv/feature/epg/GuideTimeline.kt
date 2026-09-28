package com.livewire.tv.feature.epg

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
