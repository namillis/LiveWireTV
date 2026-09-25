package com.livewire.tv.feature.providers.domain

import com.livewire.tv.feature.providers.domain.ProviderInputValidator.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderInputValidatorTest {

    @Test
    fun acceptsHttpAndHttpsWithPort() {
        assertNull(ProviderInputValidator.validate("http://provider.example:8080", "u", "p"))
        assertNull(ProviderInputValidator.validate("  https://provider.example/  ", "u", "p"))
    }

    @Test
    fun rejectsBlankUrl() {
        assertEquals(Field.URL, ProviderInputValidator.validate("   ", "u", "p")?.field)
    }

    @Test
    fun rejectsMissingOrUnsupportedScheme() {
        assertEquals(Field.URL, ProviderInputValidator.validate("provider.example:8080", "u", "p")?.field)
        assertEquals(Field.URL, ProviderInputValidator.validate("ftp://provider.example", "u", "p")?.field)
    }

    @Test
    fun rejectsMalformedUrl() {
        assertEquals(Field.URL, ProviderInputValidator.validate("http://", "u", "p")?.field)
        assertEquals(Field.URL, ProviderInputValidator.validate("http://bad host:80", "u", "p")?.field)
    }

    @Test
    fun requiresCredentials() {
        assertEquals(Field.USERNAME, ProviderInputValidator.validate("http://h", " ", "p")?.field)
        assertEquals(Field.PASSWORD, ProviderInputValidator.validate("http://h", "u", "")?.field)
    }

    @Test
    fun m3uNeedsOnlyAValidPlaylistUrl() {
        assertNull(ProviderInputValidator.validateM3u("http://h/get.php?type=m3u_plus", ""))
        assertEquals(Field.URL, ProviderInputValidator.validateM3u("", "")?.field)
        assertEquals(Field.EPG_URL, ProviderInputValidator.validateM3u("http://h/p.m3u", "guide.xml")?.field)
        assertNull(ProviderInputValidator.validateM3u("http://h/p.m3u", "https://g/epg.xml.gz"))
    }

    @Test
    fun m3uDraftKeepsPlaylistUrlVerbatimAndDropsCredentials() {
        val cfg = ProviderDraft(
            type = ProviderType.M3U, name = " ", url = " http://h/list/ ",
            username = "ignored", password = "ignored", epgUrl = "  ",
        ).toConfig("id")
        assertEquals("http://h/list/", cfg.baseUrl)
        assertEquals(ProviderDraft.DEFAULT_NAME, cfg.name)
        assertEquals("", cfg.username)
        assertNull(cfg.epgUrl)
    }
}
