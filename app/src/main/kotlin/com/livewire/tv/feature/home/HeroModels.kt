package com.livewire.tv.feature.home

import com.livewire.tv.feature.epg.domain.EpgProgramme
import java.util.concurrent.TimeUnit

/**
 * Pure logic behind the Home / Guide details (time left, elapsed, durations).
 * No Android/Compose framework calls here so it stays unit-testable.
 */

/** "36 min left", "ends soon" in the last minute, or "" when nothing is on. */
fun minutesLeftLabel(programme: EpgProgramme?, now: Long): String {
    if (programme == null) return ""
    val mins = ((programme.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "$mins min left"
}

/** "24 min elapsed" (whole minutes since start, clamped at 0). */
fun minutesElapsedLabel(programme: EpgProgramme?, now: Long): String {
    if (programme == null) return ""
    val mins = ((now - programme.startMs) / 60_000L).coerceAtLeast(0)
    return "$mins min elapsed"
}

/**
 * Whole-minute duration of [programme], for the hero "N min" readouts and the guide
 * on-now bar. Zero when the programme is null or malformed.
 */
fun durationMinutes(programme: EpgProgramme?): Long {
    if (programme == null) return 0
    return (TimeUnit.MILLISECONDS.toMinutes(programme.stopMs - programme.startMs)).coerceAtLeast(0)
}
