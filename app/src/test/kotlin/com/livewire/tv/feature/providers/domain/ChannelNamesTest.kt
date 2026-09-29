package com.livewire.tv.feature.providers.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNamesTest {

    @Test fun `marker-framed divider rows are placeholders`() {
        assertTrue(ChannelNames.isPlaceholder("##### US - NEWS #####"))
        assertTrue(ChannelNames.isPlaceholder("##### SPECTRUM NETWORK #####"))
        assertTrue(ChannelNames.isPlaceholder("##### FOX #####"))
        assertTrue(ChannelNames.isPlaceholder("===== SPORTS ====="))
        assertTrue(ChannelNames.isPlaceholder("#####"))
        assertTrue(ChannelNames.isPlaceholder("____"))
        assertTrue(ChannelNames.isPlaceholder("*** ***"))
    }

    @Test fun `blank or symbol-only names are placeholders`() {
        assertTrue(ChannelNames.isPlaceholder(""))
        assertTrue(ChannelNames.isPlaceholder("   "))
        assertTrue(ChannelNames.isPlaceholder("---"))
    }

    @Test fun `real channels survive the placeholder rule`() {
        // The names the provider divider rows sit between must never be caught.
        assertFalse(ChannelNames.isPlaceholder("US - CNN HD"))
        assertFalse(ChannelNames.isPlaceholder("US - FOX 26 HOUSTON HD"))
        assertFalse(ChannelNames.isPlaceholder("US - FOX HD"))
        assertFalse(ChannelNames.isPlaceholder("FOX 26 Houston"))
        assertFalse(ChannelNames.isPlaceholder("US|NBC CHICAGO"))
        assertFalse(ChannelNames.isPlaceholder("UK: BBC ONE"))
        assertFalse(ChannelNames.isPlaceholder("US - NBC HD \u25C9"))
    }

    @Test fun `a lone leading marker with a real label is not a placeholder`() {
        // "#1 Hits" starts with a single '#', not a run of 2+, and carries a real label.
        assertFalse(ChannelNames.isPlaceholder("#1 Hits"))
        assertFalse(ChannelNames.isPlaceholder("#1 Country"))
    }
}
