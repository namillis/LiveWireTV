package com.livewire.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInputTest {

    // --- LIVE, resting overlay (no panel) ------------------------------------------------

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

    @Test fun `media transport keys are inert on a live channel`() {
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_REW, PlayerMode.LIVE))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_FF, PlayerMode.LIVE))
    }

    // --- ARCHIVE, bar NOT focused (Option 2: panels keep Left/Right) ----------------------

    @Test fun `archive without bar focus keeps Left-Right on the panels`() {
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.CHANNELS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.LEFT, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.OPTIONS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.RIGHT, PlayerMode.ARCHIVE))
    }

    @Test fun `archive Up goes live and Down focuses the scrub bar`() {
        assertEquals(PlayerAction.GoLive, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.UP, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.FocusScrubBar, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.DOWN, PlayerMode.ARCHIVE))
    }

    @Test fun `archive OK toggles play-pause and Back exits`() {
        assertEquals(PlayerAction.TogglePlayPause, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.CENTER, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.Exit, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.BACK, PlayerMode.ARCHIVE))
    }

    @Test fun `archive media keys seek even before the bar is focused`() {
        assertEquals(PlayerAction.SeekBack, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_REW, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.SeekForward, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_FF, PlayerMode.ARCHIVE))
    }

    // --- ARCHIVE, bar FOCUSED (Option 2: Left/Right now seek) -----------------------------

    @Test fun `with the scrub bar focused Left-Right seek`() {
        assertEquals(PlayerAction.SeekBack, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.LEFT, PlayerMode.ARCHIVE, barFocused = true))
        assertEquals(PlayerAction.SeekForward, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.RIGHT, PlayerMode.ARCHIVE, barFocused = true))
    }

    @Test fun `with the scrub bar focused Up goes live and Down-Back release the bar`() {
        assertEquals(PlayerAction.GoLive, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.UP, PlayerMode.ARCHIVE, barFocused = true))
        assertEquals(PlayerAction.ReleaseScrubBar, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.DOWN, PlayerMode.ARCHIVE, barFocused = true))
        assertEquals(PlayerAction.ReleaseScrubBar, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.BACK, PlayerMode.ARCHIVE, barFocused = true))
    }

    @Test fun `with the scrub bar focused OK still toggles play-pause and media keys seek`() {
        assertEquals(PlayerAction.TogglePlayPause, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.CENTER, PlayerMode.ARCHIVE, barFocused = true))
        assertEquals(PlayerAction.SeekBack, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_REW, PlayerMode.ARCHIVE, barFocused = true))
        assertEquals(PlayerAction.SeekForward, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.MEDIA_FF, PlayerMode.ARCHIVE, barFocused = true))
    }

    // --- PAUSED_LIVE (time shift): OK resumes rather than toggling ------------------------

    @Test fun `paused-live OK resumes, Up goes live, Down focuses the bar`() {
        assertEquals(PlayerAction.Resume, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.CENTER, PlayerMode.PAUSED_LIVE))
        assertEquals(PlayerAction.GoLive, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.UP, PlayerMode.PAUSED_LIVE))
        assertEquals(PlayerAction.FocusScrubBar, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.DOWN, PlayerMode.PAUSED_LIVE))
    }

    @Test fun `paused-live keeps Left-Right on the panels until the bar is focused`() {
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.CHANNELS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.LEFT, PlayerMode.PAUSED_LIVE))
        assertEquals(PlayerAction.OpenPanel(PlayerPanel.OPTIONS), PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.RIGHT, PlayerMode.PAUSED_LIVE))
    }

    @Test fun `paused-live with the bar focused seeks on Left-Right and resumes on OK`() {
        assertEquals(PlayerAction.SeekBack, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.LEFT, PlayerMode.PAUSED_LIVE, barFocused = true))
        assertEquals(PlayerAction.SeekForward, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.RIGHT, PlayerMode.PAUSED_LIVE, barFocused = true))
        assertEquals(PlayerAction.Resume, PlayerInput.onKey(PlayerPanel.NONE, PlayerKey.CENTER, PlayerMode.PAUSED_LIVE, barFocused = true))
    }

    // --- Options panel (right) — LEFT is the opposing edge that closes it ------------------
    // A panel owns its keys REGARDLESS of mode, so the archive map never leaks into a panel.

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

    @Test fun `an open panel keeps its map even in archive mode`() {
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.LEFT, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.Ignored, PlayerInput.onKey(PlayerPanel.OPTIONS, PlayerKey.UP, PlayerMode.ARCHIVE))
        assertEquals(PlayerAction.ClosePanel, PlayerInput.onKey(PlayerPanel.CHANNELS, PlayerKey.RIGHT, PlayerMode.ARCHIVE))
    }

    // --- Channels panel (left) — RIGHT is the opposing edge that closes it -----------------

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
