package com.livewire.tv.feature.player

/**
 * Time-shift renews its recorded window on its own: shortly before playback reaches the end of
 * what the provider had recorded, and again if the stream runs dry. The user didn't ask for a
 * renewal, so it must not disturb the scrub bar: the bar keeps its focus, and a seek the user
 * is still choosing carries over into the renewed stream. A new catch-up, Start over or resume
 * from a pause still resets both.
 *
 * Pure, so the rules are unit-tested without a player.
 */
object TimeShiftRenewal {

    /**
     * Where a renewal opens the new stream. A pending scrub target wins: the renewal takes the
     * seek, so the stream opens once, where the user aimed, instead of opening at the playback
     * position and reopening for the seek (single-connection providers refuse a quick second
     * open). Otherwise the playback position, or the end of the current window when the
     * position isn't known yet.
     */
    fun startAt(pendingTargetMs: Long?, positionMs: Long, windowEndMs: Long): Long =
        pendingTargetMs ?: positionMs.takeIf { it > 0 } ?: windowEndMs

    /**
     * True from a renewal's request until its stream opens. The old stream keeps playing
     * meanwhile, but playback is about to restart at the new window's start, so presses measure
     * from there and a seek waits for the new stream. A renewal that failed to resolve
     * ([ArchiveState.error]) never opens, so it doesn't count.
     */
    fun isOpening(archive: ArchiveState, openedToken: Int): Boolean =
        archive.renewal && archive.token != openedToken && archive.error == null

    /**
     * Where scrub presses and the bar's knob start from: the window start while a renewal
     * opens ([isOpening]), else the playback position, or the window start when it isn't known.
     */
    fun playheadMs(archive: ArchiveState, openedToken: Int, positionMs: Long): Long =
        if (isOpening(archive, openedToken)) archive.startMs else positionMs.takeIf { it > 0 } ?: archive.startMs
}
