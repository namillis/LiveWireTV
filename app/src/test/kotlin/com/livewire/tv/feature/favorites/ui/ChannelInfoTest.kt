package com.livewire.tv.feature.favorites.ui

import com.livewire.tv.feature.settings.data.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cheap Channel-info chips: format from Settings, quality parsed from the channel name. */
class ChannelInfoTest {

    @Test
    fun `format label is the short form`() {
        assertEquals("TS", ChannelInfo.formatLabel(StreamFormat.TS))
        assertEquals("HLS", ChannelInfo.formatLabel(StreamFormat.HLS))
    }

    @Test
    fun `quality is parsed whole-word from the name`() {
        assertEquals("HD", ChannelInfo.qualityLabel("US - CNN HD"))
        assertEquals("FHD", ChannelInfo.qualityLabel("US - FOX FHD"))
        assertEquals("SD", ChannelInfo.qualityLabel("US - ABC SD"))
    }

    @Test
    fun `4K and UHD both report UHD`() {
        assertEquals("UHD", ChannelInfo.qualityLabel("Sports 4K"))
        assertEquals("UHD", ChannelInfo.qualityLabel("Nature UHD"))
    }

    @Test
    fun `FHD wins over HD when both could match`() {
        assertEquals("FHD", ChannelInfo.qualityLabel("Movies FHD"))
    }

    @Test
    fun `no quality marker yields null, and partial words do not match`() {
        assertNull(ChannelInfo.qualityLabel("US - CNN"))
        assertNull(ChannelInfo.qualityLabel("SHDTV"))
        assertNull(ChannelInfo.qualityLabel("4KIDS"))
    }
}
