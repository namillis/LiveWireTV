package com.livewire.tv.feature.player

/**
 * Which overlay panel, if any, is open over the video. The resting overlay (channel info,
 * hint chips) shows in [NONE]; the two side panels slide in from their edge.
 */
enum class PlayerPanel { NONE, OPTIONS, CHANNELS }

/**
 * The D-pad key set the player reducer understands. Mapped from Android key codes at the
 * Compose edge so [PlayerInput] itself has no Android dependency and stays unit-testable.
 */
enum class PlayerKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK }

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
object PlayerInput {

    fun onKey(panel: PlayerPanel, key: PlayerKey): PlayerAction = when (panel) {
        PlayerPanel.NONE -> restingKey(key)
        PlayerPanel.OPTIONS -> panelKey(key, closeOn = PlayerKey.LEFT)
        PlayerPanel.CHANNELS -> panelKey(key, closeOn = PlayerKey.RIGHT)
    }

    private fun restingKey(key: PlayerKey): PlayerAction = when (key) {
        PlayerKey.LEFT -> PlayerAction.OpenPanel(PlayerPanel.CHANNELS)
        PlayerKey.RIGHT -> PlayerAction.OpenPanel(PlayerPanel.OPTIONS)
        PlayerKey.UP -> PlayerAction.ChannelUp
        PlayerKey.DOWN -> PlayerAction.ChannelDown
        PlayerKey.CENTER -> PlayerAction.TogglePlayPause
        PlayerKey.BACK -> PlayerAction.Exit
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
