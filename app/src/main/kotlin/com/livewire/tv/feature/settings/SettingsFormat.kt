package com.livewire.tv.feature.settings

import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.feature.settings.data.StreamFormat

/**
 * Pure formatting and stepping for the Settings screen (design system §7, §9.7). No
 * Android/Compose calls so it stays unit-testable; the screen renders these strings and
 * derives the D-pad Left/Right and OK behaviour from these functions. Every value change is
 * spelled out (never colour-only, §3.3): the switch has a written On/Off state, the guide
 * window a "N hours" label, the stream format the model's own labels.
 */
object SettingsFormat {

    /** Guide window bounds (SettingsStore default is 4). Kept here so the stepper and the
     *  "Range 2 – 8" caption share one source of truth. */
    const val GUIDE_WINDOW_MIN = 2
    const val GUIDE_WINDOW_MAX = 8

    /** The guide-window value clamped into [GUIDE_WINDOW_MIN]..[GUIDE_WINDOW_MAX]. */
    fun clampGuideWindow(hours: Int): Int =
        hours.coerceIn(GUIDE_WINDOW_MIN, GUIDE_WINDOW_MAX)

    /** One step down (D-pad Left), never below the minimum. */
    fun decrementGuideWindow(hours: Int): Int = clampGuideWindow(hours - 1)

    /** One step up (D-pad Right), never above the maximum. */
    fun incrementGuideWindow(hours: Int): Int = clampGuideWindow(hours + 1)

    /** Whether the row's Left arrow can still act (value above the floor). */
    fun canDecrementGuideWindow(hours: Int): Boolean = hours > GUIDE_WINDOW_MIN

    /** Whether the row's Right arrow can still act (value below the ceiling). */
    fun canIncrementGuideWindow(hours: Int): Boolean = hours < GUIDE_WINDOW_MAX

    /** The stepper's centre label, e.g. "4 hours" (singular at 1, though the range never reaches it). */
    fun guideWindowLabel(hours: Int): String {
        val h = clampGuideWindow(hours)
        return if (h == 1) "$h hour" else "$h hours"
    }

    /** The mono caption under the guide stepper, e.g. "Range 2 – 8 · was 4". */
    fun guideWindowRangeCaption(previousHours: Int): String =
        "Range $GUIDE_WINDOW_MIN – $GUIDE_WINDOW_MAX · was ${clampGuideWindow(previousHours)}"

    /** The other stream format — what OK on the focused row cycles to (§9.7: OK cycles TS↔HLS). */
    fun toggleStreamFormat(current: StreamFormat): StreamFormat =
        if (current == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS

    /** The written state beside the now-playing switch (§7 written-state rule). */
    fun onOffLabel(on: Boolean): String = if (on) "On" else "Off"

    /** Shown when no provider is configured. */
    const val NO_PROVIDER = "No provider"

    /**
     * The active provider as "Name · Xtream" or "Name · M3U" (the first stored provider is
     * the active one). Only the user's own label and the type: never the URL or login.
     */
    fun providerSummary(providers: List<ProviderConfig>): String {
        val active = providers.firstOrNull() ?: return NO_PROVIDER
        val type = when (active.type) {
            ProviderType.XTREAM -> "Xtream"
            ProviderType.M3U -> "M3U"
        }
        val name = active.name.trim().ifEmpty { type }
        return if (name == type) type else "$name · $type"
    }
}
