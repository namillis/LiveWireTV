package com.livewire.tv.feature.epg.data

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
}
