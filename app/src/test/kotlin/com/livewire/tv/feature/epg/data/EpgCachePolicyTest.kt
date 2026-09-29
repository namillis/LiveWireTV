package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class EpgCachePolicyTest {

    private val ttl = TimeUnit.HOURS.toMillis(3)
    private val base = 1_700_000_000_000L

    @Test fun `a just-written entry is fresh`() {
        assertTrue(EpgCachePolicy.isFresh(cachedAtMs = base, nowMs = base, ttlMs = ttl))
    }

    @Test fun `an entry within the TTL is fresh`() {
        assertTrue(EpgCachePolicy.isFresh(base, base + ttl - 1, ttl))
    }

    @Test fun `an entry at exactly the TTL is stale`() {
        assertFalse(EpgCachePolicy.isFresh(base, base + ttl, ttl))
    }

    @Test fun `an entry past the TTL is stale`() {
        assertFalse(EpgCachePolicy.isFresh(base, base + ttl + 1, ttl))
    }

    @Test fun `a non-positive TTL disables the cache`() {
        assertFalse(EpgCachePolicy.isFresh(base, base, ttlMs = 0L))
        assertFalse(EpgCachePolicy.isFresh(base, base, ttlMs = -1L))
    }

    @Test fun `a future timestamp (clock moved back) is treated as stale`() {
        assertFalse(EpgCachePolicy.isFresh(cachedAtMs = base + 5_000, nowMs = base, ttlMs = ttl))
    }

    private fun cfg(id: String, base: String) =
        ProviderConfig(id = id, name = "p", baseUrl = base, username = "u", password = "pw", type = ProviderType.XTREAM)

    @Test fun `the same provider and guide url yields the same key`() {
        val c = cfg("abc", "http://host:80")
        assertEquals(EpgCachePolicy.keyFor(c, "http://host/xmltv"), EpgCachePolicy.keyFor(c, "http://host/xmltv"))
    }

    @Test fun `a different provider id yields a different key`() {
        val url = "http://host/xmltv"
        assertNotEquals(EpgCachePolicy.keyFor(cfg("a", "http://host"), url), EpgCachePolicy.keyFor(cfg("b", "http://host"), url))
    }

    @Test fun `a different guide url yields a different key (credential or endpoint change)`() {
        val c = cfg("abc", "http://host")
        assertNotEquals(EpgCachePolicy.keyFor(c, "http://host/xmltv?p=1"), EpgCachePolicy.keyFor(c, "http://host/xmltv?p=2"))
    }

    @Test fun `the key is a hex hash, never the raw url`() {
        val secretUrl = "http://host/xmltv.php?username=alice&password=hunter2"
        val key = EpgCachePolicy.keyFor(cfg("abc", "http://host"), secretUrl)
        assertTrue("key must be hex", key.matches(Regex("[0-9a-f]+")))
        assertFalse("key must not leak the url", key.contains("hunter2"))
    }

    @Test fun `different channel sets get different keys, in any order the same`() {
        val c = cfg("abc", "http://host")
        val url = "http://host/xmltv"
        assertNotEquals(EpgCachePolicy.keyFor(c, url, setOf("a")), EpgCachePolicy.keyFor(c, url, setOf("a", "b")))
        assertNotEquals(EpgCachePolicy.keyFor(c, url, null), EpgCachePolicy.keyFor(c, url, setOf("a")))
        assertEquals(EpgCachePolicy.keyFor(c, url, setOf("b", "a")), EpgCachePolicy.keyFor(c, url, linkedSetOf("a", "b")))
    }

    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test fun `a cached window covering the request is a hit`() {
        assertTrue(EpgCachePolicy.covers(base - 2 * hour, base + 15 * hour, EpgWindow(base - hour / 2, base + 4 * hour)))
    }

    @Test fun `a request running past the cached window is a miss`() {
        // Guide cached at 12:00 for 11:30-18:30; reopened at 16:00 it wants 15:30-19:30.
        assertFalse(EpgCachePolicy.covers(base, base + 7 * hour, EpgWindow(base + 4 * hour, base + 8 * hour)))
    }

    @Test fun `a request starting before the cached window is a miss`() {
        assertFalse(EpgCachePolicy.covers(base, base + 7 * hour, EpgWindow(base - hour, base + 2 * hour)))
    }

    @Test fun `an unwindowed cache covers anything, a windowed one never covers an unwindowed request`() {
        assertTrue(EpgCachePolicy.covers(null, null, EpgWindow(base, base + hour)))
        assertTrue(EpgCachePolicy.covers(null, null, null))
        assertFalse(EpgCachePolicy.covers(base, base + hour, null))
    }

    @Test fun `the download window extends the end by the TTL so a later reopen still hits`() {
        val requested = EpgWindow(base, base + 4 * hour)
        val parsed = EpgCachePolicy.downloadWindow(requested, ttl)!!
        assertEquals(base, parsed.startMs)
        assertEquals(base + 7 * hour, parsed.endMs)
        // Same screen reopened just before the TTL expires: window slid forward ~3h.
        val later = EpgWindow(base + ttl - 1, base + ttl - 1 + 4 * hour)
        assertTrue(EpgCachePolicy.covers(parsed.startMs, parsed.endMs, later))
    }
}
