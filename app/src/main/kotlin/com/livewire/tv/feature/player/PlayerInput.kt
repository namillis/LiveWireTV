package com.livewire.tv.feature.player

/**
 * Which overlay panel, if any, is open over the video. The resting overlay (channel info,
 * hint chips) shows in [NONE]; the two side panels slide in from their edge.
 */
enum class PlayerPanel { NONE, OPTIONS, CHANNELS }

/**
 * What the stream currently is, so the reducer can route the SAME key differently per mode
 * (catch-up requirement 3). The user's principle is that Left/Right mean the same thing in
 * every mode UNTIL the scrub bar is focused — see [PlayerInput].
 *
 * - [LIVE]: a live channel (today's behaviour).
 * - [ARCHIVE]: catch-up playback of a past programme.
 * - [PAUSED_LIVE]: live TV paused/rewound on an archive channel (time shift) — resuming
 *   continues from the archive at the pause point.
 */
enum class PlayerMode { LIVE, ARCHIVE, PAUSED_LIVE }

/**
 * The D-pad key set the player reducer understands. Mapped from Android key codes at the
 * Compose edge so [PlayerInput] itself has no Android dependency and stays unit-testable.
 * Media transport keys fold into [SEEK_BACK]/[SEEK_FORWARD]/[CENTER] at the edge.
 */
enum class PlayerKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK, MEDIA_REW, MEDIA_FF }

/**
 * What the player screen should DO in response to a key, decided purely from the current
 * panel state. The composable interprets these; the reducer performs no side effects.
 *
 * - [Handled]/[Ignored] only affect whether the key is consumed.
 * - [OpenPanel]/[ClosePanel] move [PlayerReducer] state.
 * - [TogglePlayPause], [ChannelUp], [ChannelDown] act on the engine/view model.
 * - [Exit] leaves the player (only when no panel is open).
 */
sealed interface PlayerAction {
    /** Key consumed, nothing else to do (the reveal side effect is applied separately). */
    data object Handled : PlayerAction
    /** Key not relevant here; let it propagate (e.g. focus movement inside a panel). */
    data object Ignored : PlayerAction
    data class OpenPanel(val panel: PlayerPanel) : PlayerAction
    data object ClosePanel : PlayerAction
    data object TogglePlayPause : PlayerAction
    data object ChannelUp : PlayerAction
    data object ChannelDown : PlayerAction
    data object Exit : PlayerAction

    // --- Catch-up / time shift (Option 2 key map) ---------------------------------------
    /** ▲ in archive / paused-live: jump back to the live edge. */
    data object GoLive : PlayerAction
    /** ▼ in archive / paused-live with the bar NOT focused: grab the scrub bar. */
    data object FocusScrubBar : PlayerAction
    /** ▼ or BACK with the scrub bar focused: let go of it (back to the resting overlay). */
    data object ReleaseScrubBar : PlayerAction
    /** ◀ with the scrub bar focused: move the seek target back ([CatchUpScrubber]). */
    data object SeekBack : PlayerAction
    /** ▶ with the scrub bar focused: move the seek target forward. */
    data object SeekForward : PlayerAction
    /** OK while paused/time-shifted: resume playback from the archive at the pause point. */
    data object Resume : PlayerAction
}

/**
 * The player's key routing (design system §9.6 and the task's requirement 4), as a pure
 * function of the current [PlayerPanel]. Keeping it here — rather than inline in the key
 * handler — makes the whole D-pad contract testable without Compose or an emulator.
 *
 * Contract, no panel open (resting overlay):
 * - LEFT opens the Channels panel, RIGHT opens the Options panel.
 * - UP/DOWN change channel (prev/next in the current category).
 * - CENTER toggles play/pause.
 * - BACK exits the player.
 *
 * Contract, a panel open:
 * - The panel owns UP/DOWN/CENTER (list navigation, selection) — the reducer returns
 *   [PlayerAction.Ignored] so Compose focus handles them inside the panel.
 * - BACK closes the panel (the composable decides whether a nested sub-list closes first).
 * - The edge key that OPPOSES the panel closes it too: RIGHT closes the Channels panel
 *   (it lives on the left, so pushing back toward the video dismisses it), LEFT closes the
 *   Options panel. The mockups label these "▶ BACK Close list" / "◀ BACK Close".
 */
/**
 * The player's key routing (catch-up requirement 3, key map "Option 2"), as a pure function
 * of the current [PlayerPanel], [PlayerMode] and whether the scrub bar is focused. Keeping it
 * here — rather than inline in the key handler — makes the whole D-pad contract testable
 * without Compose or an emulator, and lets the user change Up/Down later by editing one file.
 *
 * **Option 2 principle:** Left/Right mean the SAME thing in every mode (open the Channels /
 * Options panels) UNTIL the scrub bar is focused; only then do they seek. Seeking is thus a
 * two-step gesture — ▼ focuses the bar, then ◀/▶ seek.
 *
 * Contract, no panel open (resting overlay):
 * - LIVE: LEFT→Channels, RIGHT→Options, UP/DOWN→channel ±, CENTER→play/pause, BACK→exit.
 * - ARCHIVE / PAUSED_LIVE, bar NOT focused: LEFT→Channels, RIGHT→Options (unchanged),
 *   UP→Go live, DOWN→focus the scrub bar, CENTER→play/pause (Resume in PAUSED_LIVE),
 *   BACK→exit. MEDIA_REW/FF→seek.
 * - ARCHIVE / PAUSED_LIVE, bar FOCUSED: LEFT/RIGHT→seek back/forward (steps grow while the
 *   key is pressed or held, see [CatchUpScrubber]), UP→Go live,
 *   DOWN/BACK→release the bar, CENTER→play/pause. MEDIA_REW/FF→seek.
 *
 * Contract, a panel open (any mode):
 * - The panel owns UP/DOWN/CENTER (list navigation, selection) — the reducer returns
 *   [PlayerAction.Ignored] so Compose focus handles them inside the panel.
 * - BACK closes the panel; the edge key that OPPOSES the panel closes it too (RIGHT closes
 *   Channels, LEFT closes Options). The scrub bar is never focused while a panel is open.
 */
object PlayerInput {

    fun onKey(
        panel: PlayerPanel,
        key: PlayerKey,
        mode: PlayerMode = PlayerMode.LIVE,
        barFocused: Boolean = false,
    ): PlayerAction = when (panel) {
        PlayerPanel.OPTIONS -> panelKey(key, closeOn = PlayerKey.LEFT)
        PlayerPanel.CHANNELS -> panelKey(key, closeOn = PlayerKey.RIGHT)
        PlayerPanel.NONE -> when (mode) {
            PlayerMode.LIVE -> liveKey(key)
            PlayerMode.ARCHIVE, PlayerMode.PAUSED_LIVE ->
                if (barFocused) barFocusedKey(key, mode) else archiveRestingKey(key, mode)
        }
    }

    /** LIVE, no panel: today's map. */
    private fun liveKey(key: PlayerKey): PlayerAction = when (key) {
        PlayerKey.LEFT -> PlayerAction.OpenPanel(PlayerPanel.CHANNELS)
        PlayerKey.RIGHT -> PlayerAction.OpenPanel(PlayerPanel.OPTIONS)
        PlayerKey.UP -> PlayerAction.ChannelUp
        PlayerKey.DOWN -> PlayerAction.ChannelDown
        PlayerKey.CENTER -> PlayerAction.TogglePlayPause
        PlayerKey.BACK -> PlayerAction.Exit
        // No live-edge seek buffer, so transport keys are inert on a live channel.
        PlayerKey.MEDIA_REW, PlayerKey.MEDIA_FF -> PlayerAction.Ignored
    }

    /** ARCHIVE / PAUSED_LIVE, bar NOT focused: panels keep Left/Right; Up=Go live, Down=grab bar. */
    private fun archiveRestingKey(key: PlayerKey, mode: PlayerMode): PlayerAction = when (key) {
        PlayerKey.LEFT -> PlayerAction.OpenPanel(PlayerPanel.CHANNELS)
        PlayerKey.RIGHT -> PlayerAction.OpenPanel(PlayerPanel.OPTIONS)
        PlayerKey.UP -> PlayerAction.GoLive
        PlayerKey.DOWN -> PlayerAction.FocusScrubBar
        PlayerKey.CENTER -> if (mode == PlayerMode.PAUSED_LIVE) PlayerAction.Resume else PlayerAction.TogglePlayPause
        PlayerKey.BACK -> PlayerAction.Exit
        PlayerKey.MEDIA_REW -> PlayerAction.SeekBack
        PlayerKey.MEDIA_FF -> PlayerAction.SeekForward
    }

    /** ARCHIVE / PAUSED_LIVE, bar FOCUSED: Left/Right seek; Down/Back release the bar. */
    private fun barFocusedKey(key: PlayerKey, mode: PlayerMode): PlayerAction = when (key) {
        PlayerKey.LEFT, PlayerKey.MEDIA_REW -> PlayerAction.SeekBack
        PlayerKey.RIGHT, PlayerKey.MEDIA_FF -> PlayerAction.SeekForward
        PlayerKey.UP -> PlayerAction.GoLive
        PlayerKey.DOWN, PlayerKey.BACK -> PlayerAction.ReleaseScrubBar
        PlayerKey.CENTER -> if (mode == PlayerMode.PAUSED_LIVE) PlayerAction.Resume else PlayerAction.TogglePlayPause
    }

    private fun panelKey(key: PlayerKey, closeOn: PlayerKey): PlayerAction = when (key) {
        PlayerKey.BACK -> PlayerAction.ClosePanel
        closeOn -> PlayerAction.ClosePanel
        // UP/DOWN/CENTER and the panel's own edge belong to the focused list inside it.
        else -> PlayerAction.Ignored
    }
}

/**
 * Prev/next channel within a category list, wrapping at the ends. Pure so the neighbour
 * logic (requirement 4: ▲/▼ with no panel = prev/next in the category) is unit-tested apart
 * from playback. Returns the index to switch to, or -1 when the list can't be navigated.
 */
object ChannelNeighbours {

    /** Index after [currentIndex] in a list of [size], wrapping to 0 past the end. -1 if empty. */
    fun next(currentIndex: Int, size: Int): Int = step(currentIndex, size, +1)

    /** Index before [currentIndex], wrapping to the last item. -1 if empty. */
    fun previous(currentIndex: Int, size: Int): Int = step(currentIndex, size, -1)

    private fun step(currentIndex: Int, size: Int, delta: Int): Int {
        if (size <= 0) return -1
        // A current index outside the list (e.g. the channel was removed) restarts from the
        // matching end so ▲/▼ still moves rather than jamming.
        val safe = currentIndex.coerceIn(0, size - 1)
        return ((safe + delta) % size + size) % size
    }
}
