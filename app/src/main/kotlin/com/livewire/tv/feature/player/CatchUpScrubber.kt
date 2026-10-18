package com.livewire.tv.feature.player

/**
 * Catch-up seeking from the scrub bar. Each ◀/▶ press moves a target on the bar straight away;
 * the stream is only reopened once the presses stop for [COMMIT_DELAY_MS], so the user can keep
 * jumping while the stream loads. Presses add up from the target, not from where playback is.
 *
 * Steps grow while the user keeps going the same way: 10 s back / 30 s forward for the first
 * presses, then 1, 2, 5 and 10 minutes ([stepMs]). Holding the key repeats it, at most one step
 * per [REPEAT_INTERVAL_MS]. A pause longer than [STREAK_GAP_MS], or a change of direction,
 * starts again from the small steps.
 *
 * Pure: the caller passes the clock and the playback position, so it is unit-tested without a
 * player. The screen owns the commit timer and hands the target to the engine's seek.
 */
class CatchUpScrubber {

    /** Where the bar's knob should sit while a scrub is pending, or null when there is none. */
    var targetMs: Long? = null
        private set

    private var direction = 0
    private var streak = 0
    private var lastPressAtMs = Long.MIN_VALUE / 2
    private var lastStepAtMs = Long.MIN_VALUE / 2

    /**
     * A ◀ ([forward] false) or ▶ press at [nowMs]. [repeat] is a repeat from a held key.
     * [positionMs] is the playback position, the starting point when no scrub is pending. The
     * target stays inside [windowStartMs]..[windowEndMs]. Returns the new target, or null when a
     * held key's repeat came too soon and was dropped.
     */
    fun press(
        forward: Boolean,
        repeat: Boolean,
        nowMs: Long,
        positionMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Long? {
        val dir = if (forward) 1 else -1
        if (repeat && dir == direction && nowMs - lastStepAtMs < REPEAT_INTERVAL_MS) {
            lastPressAtMs = nowMs
            return null
        }
        val continuing = dir == direction && nowMs - lastPressAtMs <= STREAK_GAP_MS
        streak = if (continuing) streak + 1 else 1
        direction = dir
        lastPressAtMs = nowMs
        lastStepAtMs = nowMs
        val from = targetMs ?: positionMs
        val next = (from + dir * stepMs(forward, streak)).coerceIn(windowStartMs, windowEndMs)
        targetMs = next
        return next
    }

    /** The pending target to seek to, clearing it. The step size carries on if presses resume. */
    fun take(): Long? = targetMs.also { targetMs = null }

    /** Drop the pending target and the step size (Go live, a new stream). */
    fun cancel() {
        targetMs = null
        streak = 0
        direction = 0
    }

    companion object {
        /** No press for this long and the pending target is sought. */
        const val COMMIT_DELAY_MS = 900L

        /** A held key takes at most one step this often. */
        const val REPEAT_INTERVAL_MS = 150L

        /** Presses closer together than this, the same way, keep growing the step. */
        const val STREAK_GAP_MS = 1_000L

        /** The step for the [streak]-th press in a row (1-based). */
        fun stepMs(forward: Boolean, streak: Int): Long = when {
            streak <= 2 -> if (forward) 30_000L else 10_000L
            streak <= 5 -> 60_000L
            streak <= 9 -> 120_000L
            streak <= 14 -> 300_000L
            else -> 600_000L
        }
    }
}
