package com.livewire.tv.feature.update.data

import com.livewire.tv.feature.update.domain.version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("update/$name")!!
            .bufferedReader().use { it.readText() }

    @Test fun `parses the saved releases-latest fixture`() {
        val release = GitHubReleaseParser.parse(fixture("github_release_latest.json"))
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

    @Test fun `tolerates unknown fields and missing optional ones`() {
        val minimal = """{"tag_name":"v0.2.0","assets":[]}"""
        val release = GitHubReleaseParser.parse(minimal)!!
        assertEquals("v0.2.0", release.tagName)
        assertNull(release.name)
        assertNull(release.notes)
        assertTrue(release.assets.isEmpty())
    }

    @Test fun `returns null on malformed json`() {
        assertNull(GitHubReleaseParser.parse("not json at all"))
        assertNull(GitHubReleaseParser.parse("{"))
    }

    @Test fun `carries draft and prerelease flags`() {
        val draft = GitHubReleaseParser.parse("""{"tag_name":"v0.3.0","draft":true}""")!!
        assertTrue(draft.draft)
        val pre = GitHubReleaseParser.parse("""{"tag_name":"v0.3.0","prerelease":true}""")!!
        assertTrue(pre.prerelease)
    }
}
