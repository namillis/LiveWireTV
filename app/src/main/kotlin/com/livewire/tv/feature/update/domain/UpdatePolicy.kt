package com.livewire.tv.feature.update.domain

/**
 * Pure policy for WHETHER an automatic update check should run, and whether a found
 * release should be surfaced. No Android, no clock of its own — the caller passes
 * `nowMs`, the stored state, and the player-open flag, so every branch is unit-testable.
 */
object UpdatePolicy {

    /** Minimum gap between automatic checks on app start. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * Should an AUTOMATIC check run now?
     *
     *  - never while [playerOpen] (an update dialog must not appear over playback);
     *  - never when the user turned [autoCheckEnabled] off;
     *  - otherwise only if at least [CHECK_INTERVAL_MS] has passed since [lastCheckMs]
     *    ([lastCheckMs] = 0 means "never checked", so it runs).
     *
     * A MANUAL "Check for updates" press bypasses this entirely — call the checker directly.
     */
    fun shouldAutoCheck(
        nowMs: Long,
        lastCheckMs: Long,
        autoCheckEnabled: Boolean,
        playerOpen: Boolean,
    ): Boolean {
        if (playerOpen) return false
        if (!autoCheckEnabled) return false
        return nowMs - lastCheckMs >= CHECK_INTERVAL_MS
    }

    /**
     * Should a discovered [candidateVersion] be shown to the user? False when the user
     * already chose to skip exactly that version. (Version-newness is decided separately
     * by [isNewerRelease]; this only applies the skip filter.)
     */
    fun shouldSurface(candidateVersion: String, skippedVersion: String?): Boolean =
        skippedVersion == null || skippedVersion != candidateVersion
}
