package com.livewire.tv.feature.catchup.domain

/**
 * A past programme for the catch-up guide. Times are UTC epoch millis. [hasArchive] is the
 * provider's authoritative per-programme flag: even within the archive window, a very recent
 * programme may not be archived yet, so the UI only offers catch-up when this is true.
 */
data class PastProgramme(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val hasArchive: Boolean,
) {
    /** Length in whole minutes, clamped to at least 1 (for the archive URL duration). */
    val durationMin: Int get() = ((stopMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
}
