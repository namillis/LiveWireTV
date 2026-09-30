package com.livewire.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInputTest {

    // --- Resting overlay (no panel) ------------------------------------------------------

    @Test fun `left opens the channels panel, right opens options`() {
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.CHANNELS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.LEFT))
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.OPTIONS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.RIGHT))
    }

    @Test fun `up and down change channel when no panel is open`() {
        assertEquals(PlayerAction.ChannelUp, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.UP))
        assertEquals(PlayerAction.ChannelDown, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.DOWN))
    }

    @Test fun `center toggles play-pause and back exits when no panel is open`() {
        assertEquals(PlayerAction.TogglePlayPause, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.CENTER))
        assertEquals(PlayerAction.Exit, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.BACK))
    }

    // --- Options panel (right) — LEFT is the opposing edge that closes it -----------------

    @Test fun `options panel closes on back and on the opposing left edge`() {
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.BACK))
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.LEFT))
    }

    @Test fun `options panel leaves list navigation to the focused list`() {
        // UP/DOWN/CENTER and the panel's own edge (RIGHT) belong to Compose focus inside it.
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.UP))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.DOWN))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.CENTER))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.RIGHT))
    }

    // --- Channels panel (left) — RIGHT is the opposing edge that closes it ----------------

    @Test fun `channels panel closes on back and on the opposing right edge`() {
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.BACK))
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.RIGHT))
    }

    @Test fun `channels panel leaves list navigation to the focused list`() {
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.UP))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.DOWN))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.CENTER))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.LEFT))
    }
}

class ChannelNeighboursTest {

    @Test fun `next wraps to the start past the end`() {
        assertEquals(1, ChannelNeighbours.next(0, 3))
        assertEquals(2, ChannelNeighbours.next(1, 3))
        assertEquals(0, ChannelNeighbours.next(2, 3))
    }

    @Test fun `previous wraps to the last item before the start`() {
        assertEquals(2, ChannelNeighbours.previous(0, 3))
        assertEquals(0, ChannelNeighbours.previous(1, 3))
        assertEquals(1, ChannelNeighbours.previous(2, 3))
    }

    @Test fun `an empty list has no neighbour`() {
        assertEquals(-1, ChannelNeighbours.next(0, 0))
        assertEquals(-1, ChannelNeighbours.previous(0, 0))
    }

    @Test fun `a current index outside the list still steps from the nearest end`() {
        // -1 (channel not in list) clamps to 0, so next is 1 and previous wraps to the last.
        assertEquals(1, ChannelNeighbours.next(-1, 3))
        assertEquals(2, ChannelNeighbours.previous(-1, 3))
    }

    @Test fun `a single-channel list stays on itself`() {
        assertEquals(0, ChannelNeighbours.next(0, 1))
        assertEquals(0, ChannelNeighbours.previous(0, 1))
    }
}
