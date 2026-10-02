package com.livewire.tv.core.player

/**
 * Pure archive seek/position arithmetic and seek debounce — no ExoPlayer, no Android, no
 * ambient clock. [ExoPlayerEngine] owns one of these in ARCHIVE mode; it is unit-tested with
 * a fake clock and fake player position so the tricky parts (absolute-time mapping, clamping,
 * reopen-vs-native decision, and holding ◀◀ collapsing to one reopen) don't need a device.
 *
 * Model: an archive window is the absolute UTC span [startMs, endMs). The stream currently
 * open was opened at some absolute start [openStartMs]; the player reports a relative position
 * within it. Absolute position = openStartMs + playerPositionMs.
 */
class ArchiveSeekController(
    val windowStartMs: Long,
    val windowEndMs: Long,
    openStartMs: Long,
    private val seekDebounceMs: Long = 600,
) {
    /** Absolute UTC start of the stream currently open. Reopening a seek target updates this. */
    var openStartMs: Long = openStartMs
        private set

    /** The pending absolute seek target while a debounce is in flight, or null when idle. */
    private var pendingTargetMs: Long? = null
    private var lastRequestAtMs: Long = 0
    private var hasRequested: Boolean = false

    /** Absolute UTC position given the player's relative position within the open stream. */
    fun absolutePosition(playerPositionMs: Long): Long =
        (openStartMs + playerPositionMs).coerceIn(windowStartMs, windowEndMs)

    /** Length of the whole archive window. */
    val durationMs: Long get() = windowEndMs - windowStartMs

    /** Elapsed millis into the window at [absolutePositionMs]. */
    fun elapsedMs(absolutePositionMs: Long): Long =
        (absolutePositionMs - windowStartMs).coerceIn(0, durationMs)

    /** Clamp an absolute target into the window. */
    fun clamp(targetMs: Long): Long = targetMs.coerceIn(windowStartMs, windowEndMs)

    /**
     * What the engine should do for a requested absolute seek target. When [seekable] and the
     * target is within [bufferedRangeMs] of the current open-stream position, seek natively;
     * otherwise the stream must be reopened at the target start. The result carries the clamped
     * target either way.
     */
    fun resolveSeek(
        targetMs: Long,
        currentAbsoluteMs: Long,
        seekable: Boolean,
        bufferedAheadMs: Long,
        bufferedBehindMs: Long,
    ): SeekDecision {
        val clamped = clamp(targetMs)
        val delta = clamped - currentAbsoluteMs
        val withinBuffer = delta in -bufferedBehindMs..bufferedAheadMs
        return if (seekable && withinBuffer) {
            // Native seek: position within the currently open stream.
            SeekDecision.Native(relativePositionMs = clamped - openStartMs, absoluteTargetMs = clamped)
        } else {
            SeekDecision.Reopen(absoluteTargetMs = clamped)
        }
    }

    /**
     * Register a seek request at [nowMs] for absolute [targetMs]. Repeated presses within the
     * debounce window collapse into ONE pending target (the latest), so holding ◀◀ doesn't open
     * a stream per press. Returns the target to ACT on now, or null when the request is being
     * debounced (the caller should schedule [takePending] after the debounce elapses).
     */
    fun requestSeek(targetMs: Long, nowMs: Long): Long? {
        val clamped = clamp(targetMs)
        val sinceLast = nowMs - lastRequestAtMs
        val firstOrSettled = !hasRequested || sinceLast >= seekDebounceMs
        lastRequestAtMs = nowMs
        hasRequested = true
        return if (firstOrSettled) {
            pendingTargetMs = null
            clamped
        } else {
            pendingTargetMs = clamped
            null
        }
    }

    /** The debounced target accumulated since the last acted-on seek, clearing it. Null when none. */
    fun takePending(): Long? {
        val t = pendingTargetMs
        pendingTargetMs = null
        return t
    }

    /** Note that the stream was reopened at [absoluteStartMs]; subsequent positions are relative to it. */
    fun onReopenedAt(absoluteStartMs: Long) {
        openStartMs = clamp(absoluteStartMs)
    }

    sealed interface SeekDecision {
        val absoluteTargetMs: Long
        data class Native(val relativePositionMs: Long, override val absoluteTargetMs: Long) : SeekDecision
        data class Reopen(override val absoluteTargetMs: Long) : SeekDecision
    }
}
