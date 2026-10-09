package com.livewire.tv.feature.update.data

import com.livewire.tv.feature.update.domain.version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("update/$name")!!
            .bufferedReader().use { it.readText() }

    @Test fun `parses the saved releases-latest fixture`() {
        val release = ReleaseParser.parse(fixture("github_release_latest.json"))
        assertNotNull(release)
        release!!
        assertEquals("v0.1.4", release.tagName)
        assertEquals("0.1.4", release.version)
        assertEquals("LiveWire v0.1.4", release.name)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertTrue(release.notes!!.contains("Favourites"))
        assertNotNull(release.publishedAtEpochMs)

        // Assets: apk, its sha256 sidecar, and update.json.
        assertEquals(3, release.assets.size)
        val apk = release.assets.first { it.name == "livewire-0.1.4.apk" }
        assertEquals(3671234L, apk.sizeBytes)
        assertTrue(apk.downloadUrl.endsWith("/livewire-0.1.4.apk"))
        assertTrue(release.assets.any { it.name == "livewire-0.1.4.apk.sha256" })
    }

    @Test fun `parses a Forgejo releases-latest response`() {
        val release = ReleaseParser.parse(fixture("forgejo_release_latest.json"))
        assertNotNull(release)
        release!!
        assertEquals("v0.2.2", release.tagName)
        assertEquals("0.2.2", release.version)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        // Forgejo writes the server's zone offset rather than Z.
        assertEquals(java.time.Instant.parse("2026-10-09T19:20:00Z").toEpochMilli(), release.publishedAtEpochMs)

        assertEquals(3, release.assets.size)
        val apk = release.assets.first { it.name == "livewire-0.2.2.apk" }
        assertEquals(3750123L, apk.sizeBytes)
        assertEquals(
            "https://git.namillis.me/namillis/LiveWireTV/releases/download/v0.2.2/livewire-0.2.2.apk",
            apk.downloadUrl,
        )
        assertTrue(release.assets.any { it.name == "livewire-0.2.2.apk.sha256" })
    }

    @Test fun `parses both Z and offset timestamps`() {
        val z = parseIso8601("2026-10-09T19:20:00Z")
        assertNotNull(z)
        assertEquals(z, parseIso8601("2026-10-09T15:20:00-04:00"))
        assertEquals(z, parseIso8601("2026-10-09T21:20:00+02:00"))
        assertNull(parseIso8601("yesterday"))
        assertNull(parseIso8601(null))
    }

    @Test fun `tolerates unknown fields and missing optional ones`() {
        val minimal = """{"tag_name":"v0.2.0","assets":[]}"""
        val release = ReleaseParser.parse(minimal)!!
        assertEquals("v0.2.0", release.tagName)
        assertNull(release.name)
        assertNull(release.notes)
        assertTrue(release.assets.isEmpty())
    }

    @Test fun `returns null on malformed json`() {
        assertNull(ReleaseParser.parse("not json at all"))
        assertNull(ReleaseParser.parse("{"))
    }

    @Test fun `carries draft and prerelease flags`() {
        val draft = ReleaseParser.parse("""{"tag_name":"v0.3.0","draft":true}""")!!
        assertTrue(draft.draft)
        val pre = ReleaseParser.parse("""{"tag_name":"v0.3.0","prerelease":true}""")!!
        assertTrue(pre.prerelease)
    }
}
