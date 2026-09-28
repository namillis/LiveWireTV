package com.livewire.tv.feature.settings

import com.livewire.tv.feature.settings.data.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormatTest {

    @Test fun `clamp keeps values inside 2 to 8`() {
        assertEquals(2, SettingsFormat.clampGuideWindow(0))
        assertEquals(2, SettingsFormat.clampGuideWindow(1))
        assertEquals(2, SettingsFormat.clampGuideWindow(2))
        assertEquals(5, SettingsFormat.clampGuideWindow(5))
        assertEquals(8, SettingsFormat.clampGuideWindow(8))
        assertEquals(8, SettingsFormat.clampGuideWindow(9))
        assertEquals(8, SettingsFormat.clampGuideWindow(99))
    }

    @Test fun `decrement steps down but stops at the floor`() {
        assertEquals(4, SettingsFormat.decrementGuideWindow(5))
        assertEquals(2, SettingsFormat.decrementGuideWindow(3))
        assertEquals(2, SettingsFormat.decrementGuideWindow(2))
        // even an out-of-range value is pulled back into bounds
        assertEquals(2, SettingsFormat.decrementGuideWindow(1))
    }

    @Test fun `increment steps up but stops at the ceiling`() {
        assertEquals(6, SettingsFormat.incrementGuideWindow(5))
        assertEquals(8, SettingsFormat.incrementGuideWindow(7))
        assertEquals(8, SettingsFormat.incrementGuideWindow(8))
        assertEquals(8, SettingsFormat.incrementGuideWindow(9))
    }

    @Test fun `arrow enablement reflects the bounds`() {
        assertFalse(SettingsFormat.canDecrementGuideWindow(2))
        assertTrue(SettingsFormat.canDecrementGuideWindow(3))
        assertTrue(SettingsFormat.canIncrementGuideWindow(7))
        assertFalse(SettingsFormat.canIncrementGuideWindow(8))
    }

    @Test fun `guide window label pluralises`() {
        assertEquals("4 hours", SettingsFormat.guideWindowLabel(4))
        assertEquals("2 hours", SettingsFormat.guideWindowLabel(2))
        assertEquals("8 hours", SettingsFormat.guideWindowLabel(8))
        // clamped before formatting so the label is always in-range
        assertEquals("8 hours", SettingsFormat.guideWindowLabel(12))
    }

    @Test fun `range caption names the bounds and the previous value`() {
        assertEquals("Range 2 – 8 · was 4", SettingsFormat.guideWindowRangeCaption(4))
        assertEquals("Range 2 – 8 · was 5", SettingsFormat.guideWindowRangeCaption(5))
        // previous value is clamped into range too
        assertEquals("Range 2 – 8 · was 8", SettingsFormat.guideWindowRangeCaption(20))
    }

    @Test fun `stream format toggles both ways`() {
        assertEquals(StreamFormat.HLS, SettingsFormat.toggleStreamFormat(StreamFormat.TS))
        assertEquals(StreamFormat.TS, SettingsFormat.toggleStreamFormat(StreamFormat.HLS))
    }

    @Test fun `on off label is written out`() {
        assertEquals("On", SettingsFormat.onOffLabel(true))
        assertEquals("Off", SettingsFormat.onOffLabel(false))
    }
}
