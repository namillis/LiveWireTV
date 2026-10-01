package com.livewire.tv.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetSelectionTest {

    private fun asset(name: String, size: Long = 100) =
        ReleaseAsset(name = name, sizeBytes = size, downloadUrl = "https://example/$name")

    private val pattern = "livewire-%s.apk"

    @Test fun `picks the exactly-named apk and its sha256 sidecar`() {
        val assets = listOf(
            asset("livewire-0.1.4.apk", 3500),
            asset("livewire-0.1.4.apk.sha256", 90),
            asset("update.json", 120),
        )
        val sel = selectAssets(assets, "0.1.4", pattern)!!
        assertEquals("livewire-0.1.4.apk", sel.apk.name)
        assertEquals(3500, sel.apk.sizeBytes)
        assertEquals("livewire-0.1.4.apk.sha256", sel.sha256Asset!!.name)
    }

    @Test fun `falls back to the single apk when the exact name is absent`() {
        val assets = listOf(asset("app-release.apk"), asset("app-release.apk.sha256"))
        val sel = selectAssets(assets, "0.1.4", pattern)!!
        assertEquals("app-release.apk", sel.apk.name)
        assertEquals("app-release.apk.sha256", sel.sha256Asset!!.name)
    }

    @Test fun `null sidecar when no sha256 is published`() {
        val sel = selectAssets(listOf(asset("livewire-0.1.4.apk")), "0.1.4", pattern)!!
        assertNull(sel.sha256Asset)
    }

    @Test fun `ambiguous multiple apks with no exact match returns null`() {
        val assets = listOf(asset("one.apk"), asset("two.apk"))
        assertNull(selectAssets(assets, "0.1.4", pattern))
    }

    @Test fun `no apk at all returns null`() {
        assertNull(selectAssets(listOf(asset("notes.txt")), "0.1.4", pattern))
    }
}

class Sha256ParseTest {

    private val digest = "a".repeat(64)

    @Test fun `parses standard sha256sum two-space format`() {
        assertEquals(digest, parseSha256("$digest  livewire-0.1.4.apk"))
    }

    @Test fun `parses single-space and binary-star markers`() {
        assertEquals(digest, parseSha256("$digest livewire-0.1.4.apk"))
        assertEquals(digest, parseSha256("$digest *livewire-0.1.4.apk"))
    }

    @Test fun `parses a bare digest with no filename`() {
        assertEquals(digest, parseSha256(digest))
        assertEquals(digest, parseSha256("$digest\n"))
    }

    @Test fun `uppercases are normalised to lower`() {
        assertEquals(digest, parseSha256(("A".repeat(64)) + "  file.apk"))
    }

    @Test fun `rejects wrong length or non-hex`() {
        assertNull(parseSha256("abc123"))
        assertNull(parseSha256(("g".repeat(64)) + "  file.apk"))
        assertNull(parseSha256(""))
        assertNull(parseSha256(null))
    }

    @Test fun `skips blank leading lines`() {
        assertEquals(digest, parseSha256("\n\n$digest  file.apk"))
    }
}
