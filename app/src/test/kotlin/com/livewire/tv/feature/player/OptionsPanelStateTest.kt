package com.livewire.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsPanelStateTest {

    @Test fun `a fresh open focuses Source when the channel has other streams`() {
        val state = OptionsPanelState()
        state.start()
        assertEquals(OptionsView.ROOT, state.view)
        assertNull(state.lastOpened)
        assertEquals(OptionsView.SOURCE, state.rootTarget(hasSourceRow = true))
    }

    @Test fun `a fresh open focuses Audio when there is no Source row`() {
        val state = OptionsPanelState()
        state.start()
        assertEquals(OptionsView.AUDIO, state.rootTarget(hasSourceRow = false))
    }

    @Test fun `focus moves up to Source when the streams arrive and the user has not moved`() {
        val state = OptionsPanelState()
        state.start()
        assertEquals(OptionsView.AUDIO, state.rootTarget(hasSourceRow = false))
        state.onRootRowFocused(OptionsView.AUDIO)
        assertEquals(OptionsView.SOURCE, state.rootTarget(hasSourceRow = true))
    }

    @Test fun `focus stays put when the streams arrive after the user moved`() {
        val state = OptionsPanelState()
        state.start()
        state.rootTarget(hasSourceRow = false)
        state.onRootRowFocused(OptionsView.AUDIO)
        state.onRootRowFocused(OptionsView.SUBTITLES)
        assertNull(state.rootTarget(hasSourceRow = true))
    }

    @Test fun `moving to Favourite counts as the user moving`() {
        val state = OptionsPanelState()
        state.start()
        state.rootTarget(hasSourceRow = false)
        state.onRootRowFocused(OptionsView.AUDIO)
        state.onRootRowFocused(null)
        assertNull(state.rootTarget(hasSourceRow = true))
    }

    @Test fun `a row focused before the panel's own pick lands is not the user moving`() {
        val state = OptionsPanelState()
        state.start()
        state.rootTarget(hasSourceRow = false)
        state.onRootRowFocused(null)
        assertEquals(OptionsView.SOURCE, state.rootTarget(hasSourceRow = true))
    }

    @Test fun `back from a sub-list returns to the root and refocuses its row`() {
        val state = OptionsPanelState()
        state.start()
        state.rootTarget(hasSourceRow = true)
        state.onRootRowFocused(OptionsView.SOURCE)
        state.onRootRowFocused(OptionsView.SUBTITLES)
        state.open(OptionsView.SUBTITLES)
        assertTrue(state.onBack())
        assertEquals(OptionsView.ROOT, state.view)
        assertEquals(OptionsView.SUBTITLES, state.rootTarget(hasSourceRow = true))
    }

    @Test fun `back at the root is left for the screen to close the panel`() {
        val state = OptionsPanelState()
        state.start()
        assertFalse(state.onBack())
    }

    @Test fun `reopening the panel forgets the last sub-list and the user's moves`() {
        val state = OptionsPanelState()
        state.start()
        state.rootTarget(hasSourceRow = true)
        state.onRootRowFocused(OptionsView.SOURCE)
        state.onRootRowFocused(OptionsView.PICTURE)
        state.open(OptionsView.PICTURE)
        state.reset()
        state.start()
        assertNull(state.lastOpened)
        assertEquals(OptionsView.SOURCE, state.rootTarget(hasSourceRow = true))
    }
}
