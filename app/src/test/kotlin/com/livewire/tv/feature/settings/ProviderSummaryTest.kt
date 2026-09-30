package com.livewire.tv.feature.settings

import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderSummaryTest {

    private fun cfg(name: String, type: ProviderType, url: String = "http://panel.example.com:8080") =
        ProviderConfig(id = name + type, name = name, baseUrl = url, username = "user123", password = "secret", type = type)

    @Test
    fun `active provider is the first stored one`() {
        val all = listOf(cfg("My Provider", ProviderType.XTREAM), cfg("iptv-org US", ProviderType.M3U))
        assertEquals("My Provider · Xtream", SettingsFormat.providerSummary(all))
    }

    @Test
    fun `m3u provider shows its type`() {
        assertEquals("iptv-org US · M3U", SettingsFormat.providerSummary(listOf(cfg("iptv-org US", ProviderType.M3U))))
    }

    @Test
    fun `no providers`() {
        assertEquals(SettingsFormat.NO_PROVIDER, SettingsFormat.providerSummary(emptyList()))
    }

    @Test
    fun `blank name falls back to the type alone`() {
        assertEquals("Xtream", SettingsFormat.providerSummary(listOf(cfg("  ", ProviderType.XTREAM))))
    }

    @Test
    fun `summary never contains the url or login`() {
        val s = SettingsFormat.providerSummary(listOf(cfg("Home", ProviderType.XTREAM)))
        assertFalse(s.contains("example.com"))
        assertFalse(s.contains("user123"))
        assertFalse(s.contains("secret"))
    }
}
