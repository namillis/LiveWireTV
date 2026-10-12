package com.livewire.tv.core.player

/**
 * How often a live stream that played and then froze may move to another source on its own:
 * at most [maxSwitches] in any [windowMs], after which the player shows its error and leaves the
 * next move to the user. Times are monotonic millis (SystemClock.elapsedRealtime in the app).
 */
class StallPolicy(
    private val maxSwitches: Int = 2,
    private val windowMs: Long = 60_000L,
) {
    private val switches = ArrayDeque<Long>()

    /** A new channel or a user retry starts with the full allowance again. */
    fun reset() = switches.clear()

    /** True when a stall at [nowMs] may switch source, and records that switch. */
    fun allowSwitch(nowMs: Long): Boolean {
        while (switches.isNotEmpty() && nowMs - switches.first() >= windowMs) switches.removeFirst()
        if (switches.size >= maxSwitches) return false
        switches.addLast(nowMs)
        return true
    }
}
