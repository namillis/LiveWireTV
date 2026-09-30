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

    // ── normalizeUrl ──

    @Test
    fun normalizeAddsHttpWhenSchemeMissing() {
        assertEquals("http://provider.example:8080", ProviderInputValidator.normalizeUrl("provider.example:8080"))
        assertEquals("http://provider.example", ProviderInputValidator.normalizeUrl("  provider.example  "))
    }

    @Test
    fun normalizeKeepsExistingSchemeAndStripsOneTrailingSlash() {
        assertEquals("https://provider.example", ProviderInputValidator.normalizeUrl("https://provider.example/"))
        assertEquals("http://h/path", ProviderInputValidator.normalizeUrl("http://h/path/"))
        // The scheme's own "//" is never stripped.
        assertEquals("http://h", ProviderInputValidator.normalizeUrl("http://h"))
    }

    @Test
    fun normalizeLeavesBlankInputAlone() {
        assertEquals("", ProviderInputValidator.normalizeUrl("   "))
    }

    // ── parseXtreamLink ──

    @Test
    fun parsesGetPhpLinkIntoServerUserPassword() {
        val creds = ProviderInputValidator.parseXtreamLink(
            "http://provider.example:8080/get.php?username=demo_user&password=secretpass&type=m3u_plus&output=ts",
        )
        assertEquals("http://provider.example:8080", creds?.server)
        assertEquals("demo_user", creds?.username)
        assertEquals("secretpass", creds?.password)
    }

    @Test
    fun parsesPlayerApiLinkWithoutPort() {
        val creds = ProviderInputValidator.parseXtreamLink(
            "http://provider.example/player_api.php?username=demo_user&password=secretpass",
        )
        // Default port 80 is dropped from the rebuilt server root.
        assertEquals("http://provider.example", creds?.server)
        assertEquals("demo_user", creds?.username)
        assertEquals("secretpass", creds?.password)
    }

    @Test
    fun parsesLinkWithMissingSchemeViaNormalisation() {
        val creds = ProviderInputValidator.parseXtreamLink(
            "provider.example:8080/get.php?username=demo_user&password=secretpass",
        )
        assertEquals("http://provider.example:8080", creds?.server)
        assertEquals("demo_user", creds?.username)
    }

    @Test
    fun keepsNonDefaultHttpsPort() {
        val creds = ProviderInputValidator.parseXtreamLink(
            "https://provider.example:8443/get.php?username=demo_user&password=secretpass",
        )
        assertEquals("https://provider.example:8443", creds?.server)
    }

    @Test
    fun dropsDefaultHttpsPort() {
        val creds = ProviderInputValidator.parseXtreamLink(
            "https://provider.example:443/player_api.php?username=demo_user&password=secretpass",
        )
        assertEquals("https://provider.example", creds?.server)
    }

    @Test
    fun rejectsNonXtreamOrIncompleteLinks() {
        // A plain server URL, not a get.php/player_api.php link.
        assertNull(ProviderInputValidator.parseXtreamLink("http://provider.example:8080"))
        // get.php but no credentials.
        assertNull(ProviderInputValidator.parseXtreamLink("http://provider.example/get.php?type=m3u_plus"))
        // Missing password.
        assertNull(ProviderInputValidator.parseXtreamLink("http://provider.example/get.php?username=demo_user"))
        // Blank.
        assertNull(ProviderInputValidator.parseXtreamLink("   "))
    }
}
