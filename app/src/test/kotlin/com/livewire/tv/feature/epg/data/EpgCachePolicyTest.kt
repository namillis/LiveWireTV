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

    @Test fun `the cache key is per provider and url, not per channel set`() {
        val c = cfg("abc", "http://host")
        val url = "http://host/xmltv"
        // One provider has one guide; every screen shares the entry regardless of its channels.
        assertEquals(EpgCachePolicy.keyFor(c, url), EpgCachePolicy.keyFor(c, url))
    }

    @Test fun `an all-channels entry covers any request, including all-channels`() {
        assertTrue(EpgCachePolicy.coversChannels(cachedIds = null, requested = setOf("a", "b")))
        assertTrue(EpgCachePolicy.coversChannels(cachedIds = null, requested = null))
    }

    @Test fun `a filtered entry covers a subset but not a superset or an all-channels request`() {
        val cached = setOf("a", "b", "c")
        assertTrue(EpgCachePolicy.coversChannels(cached, setOf("a", "b")))
        assertTrue(EpgCachePolicy.coversChannels(cached, cached))
        assertFalse(EpgCachePolicy.coversChannels(cached, setOf("a", "d")))  // d not present
        assertFalse(EpgCachePolicy.coversChannels(cached, null))             // caller wants all
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

    @Test fun `the download window is a superset - lead back, trail plus TTL forward - so a sibling screen and later reopen still hit`() {
        val requested = EpgWindow(base, base + 4 * hour)
        val parsed = EpgCachePolicy.downloadWindow(requested, ttl)!!
        assertEquals(base - EpgCachePolicy.SUPERSET_LEAD_MS, parsed.startMs)
        assertEquals(base + 4 * hour + EpgCachePolicy.SUPERSET_TRAIL_MS + ttl, parsed.endMs)
        // Same screen reopened just before the TTL expires: window slid forward ~3h.
        val later = EpgWindow(base + ttl - 1, base + ttl - 1 + 4 * hour)
        assertTrue(EpgCachePolicy.covers(parsed.startMs, parsed.endMs, later))
        // A Guide parse (now-0.5h .. now+4h) covers a Search request (now-2h .. now+6h).
        val guideParsed = EpgCachePolicy.downloadWindow(EpgWindow(base - hour / 2, base + 4 * hour), ttl)!!
        val searchWants = EpgWindow(base - 2 * hour, base + 6 * hour)
        assertTrue(EpgCachePolicy.covers(guideParsed.startMs, guideParsed.endMs, searchWants))
    }

    @Test fun `the provider hash is stable, short hex and never the raw id`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val h = EpgCachePolicy.providerHashFor(id)
        assertEquals(h, EpgCachePolicy.providerHashFor(id))
        assertTrue("hash must be hex", h.matches(Regex("[0-9a-f]+")))
        assertEquals("8 bytes -> 16 hex chars", 16, h.length)
        assertFalse("must not leak the id", h.contains("1111"))
    }

    @Test fun `different provider ids get different hashes`() {
        assertNotEquals(EpgCachePolicy.providerHashFor("a"), EpgCachePolicy.providerHashFor("b"))
    }

    @Test fun `the file name is providerHash underscore key dot json`() {
        val id = "prov-1"
        val key = "abcdef0123456789"
        val name = EpgCachePolicy.fileNameFor(id, key)
        assertEquals("${EpgCachePolicy.providerHashFor(id)}_$key.json", name)
        assertTrue(name.startsWith(EpgCachePolicy.providerPrefixFor(id)))
        assertTrue(name.endsWith(".json"))
    }

    @Test fun `the file name never contains the url or credentials`() {
        val c = cfg("prov-1", "http://host")
        val secretUrl = "http://host/xmltv.php?username=alice&password=hunter2"
        val name = EpgCachePolicy.fileNameFor("prov-1", EpgCachePolicy.keyFor(c, secretUrl))
        assertFalse(name.contains("hunter2"))
        assertFalse(name.contains("alice"))
        assertFalse(name.contains("host"))
        assertTrue("only hex, underscore and .json", name.matches(Regex("[0-9a-f]+_[0-9a-f]+\\.json")))
    }

    @Test fun `isForProvider matches only that provider's prefixed files`() {
        val mine = EpgCachePolicy.fileNameFor("prov-1", "deadbeef")
        val other = EpgCachePolicy.fileNameFor("prov-2", "deadbeef")
        assertTrue(EpgCachePolicy.isForProvider(mine, "prov-1"))
        assertFalse(EpgCachePolicy.isForProvider(other, "prov-1"))
        assertTrue(EpgCachePolicy.isForProvider(other, "prov-2"))
    }

    @Test fun `isForProvider ignores old-format unprefixed files`() {
        // A bare <key>.json written before the prefix existed must never be claimed by any provider.
        assertFalse(EpgCachePolicy.isForProvider("abcdef0123456789.json", "prov-1"))
        assertFalse(EpgCachePolicy.isForProvider("abcdef0123456789.json", "prov-2"))
    }

    @Test fun `isForProvider rejects non-json and near-miss names`() {
        val prefix = EpgCachePolicy.providerPrefixFor("prov-1")
        assertFalse(EpgCachePolicy.isForProvider("${prefix}key.txt", "prov-1"))
        assertFalse(EpgCachePolicy.isForProvider("key.json", "prov-1"))
    }

    // --- The pool's guide_window -------------------------------------------------------------

    private val minute = TimeUnit.MINUTES.toMillis(1)

    /** 2026-11-09 14:00 UTC, on the hour. */
    private val h14 = 1_794_232_800_000L

    @Test fun `the Guide's window rounds outward to whole hours`() {
        // 14:46: the Guide's 4-hour view from 14:00 is parsed over 12:00 .. 03:00 next day.
        val now = h14 + 46 * minute
        val parsed = EpgCachePolicy.downloadWindow(EpgWindow(h14, h14 + 4 * hour), ttl)!!
        val request = EpgCachePolicy.poolGuideRequest(parsed, now)
        assertEquals(EpgCachePolicy.PoolGuideRequest(pastHours = 2, futureHours = 12), request)
        // The pool's window, 12:00 .. 03:00, holds all of it.
        assertEquals(parsed, EpgCachePolicy.poolGuideCovered(request, parsed, serverNowMs = now))
    }

    @Test fun `Home's window asks for 4 hours back and 21 ahead`() {
        val now = h14 + 46 * minute
        val parsed = EpgCachePolicy.downloadWindow(EpgWindow(now - 2 * hour, now + 12 * hour), ttl)!!
        assertEquals(EpgCachePolicy.PoolGuideRequest(4, 21), EpgCachePolicy.poolGuideRequest(parsed, now))
    }

    @Test fun `a window starting on the hour needs no extra hour`() {
        val parsed = EpgWindow(h14 - 2 * hour, h14 + 3 * hour)
        assertEquals(EpgCachePolicy.PoolGuideRequest(2, 2), EpgCachePolicy.poolGuideRequest(parsed, h14))
    }

    @Test fun `requests are clamped to the pool's limits, and the held window says so`() {
        val now = h14 + 10 * minute
        val parsed = EpgWindow(now - 10 * hour, now + 48 * hour)
        val request = EpgCachePolicy.poolGuideRequest(parsed, now)
        assertEquals(EpgCachePolicy.PoolGuideRequest(6, 36), request)
        // 08:00 .. 03:00 two days on: the parts of the parsed window past 6 back and 36 ahead are missing.
        assertEquals(
            EpgWindow(h14 - 6 * hour, h14 + 37 * hour),
            EpgCachePolicy.poolGuideCovered(request, parsed, now),
        )
    }

    @Test fun `a window wholly in the future asks for no past hours and at least one ahead`() {
        val parsed = EpgWindow(h14 + 2 * hour, h14 + 90 * minute + 2 * hour)
        assertEquals(EpgCachePolicy.PoolGuideRequest(0, 3), EpgCachePolicy.poolGuideRequest(parsed, h14 + 5 * minute))
        val soon = EpgWindow(h14 + 10 * minute, h14 + 20 * minute)
        assertEquals(EpgCachePolicy.PoolGuideRequest(0, 1), EpgCachePolicy.poolGuideRequest(soon, h14 + 5 * minute))
    }

    @Test fun `a pool clock an hour ahead holds less of the past and more of the future`() {
        // The TV thinks it is 14:58; the pool is already at 15:00 (a clock off by 2 minutes).
        val parsed = EpgWindow(h14 - 2 * hour - 20 * minute, h14 + 10 * hour)
        val request = EpgCachePolicy.poolGuideRequest(parsed, h14 + 58 * minute)
        assertEquals(EpgCachePolicy.PoolGuideRequest(3, 9), request)
        // The pool sent 12:00 .. 01:00, so the 20 minutes before 12:00 are missing.
        assertEquals(EpgWindow(h14 - 2 * hour, parsed.endMs), EpgCachePolicy.poolGuideCovered(request, parsed, h14 + hour + 2 * minute))
    }

    @Test fun `a pool clock an hour behind holds less of the future`() {
        val parsed = EpgWindow(h14 - 2 * hour, h14 + 10 * hour + 30 * minute)
        val request = EpgCachePolicy.poolGuideRequest(parsed, h14 + 1 * minute)
        assertEquals(EpgCachePolicy.PoolGuideRequest(2, 10), request)
        // The pool, still at 13:59, sent 11:00 .. 00:00: the last 30 minutes are missing.
        assertEquals(EpgWindow(parsed.startMs, h14 + 10 * hour), EpgCachePolicy.poolGuideCovered(request, parsed, h14 - 1 * minute))
    }

    @Test fun `the held window is never wider than what either side has`() {
        // Every pairing of TV clock, pool clock (up to 50 minutes apart) and window shape.
        val rnd = java.util.Random(7)
        repeat(5_000) {
            val now = h14 + rnd.nextInt(24 * 60) * minute
            val serverNow = now + (rnd.nextInt(101) - 50) * minute
            val start = now - rnd.nextInt(10 * 60) * minute
            val parsed = EpgWindow(start, start + (1 + rnd.nextInt(48 * 60)) * minute)
            val request = EpgCachePolicy.poolGuideRequest(parsed, now)
            val held = EpgCachePolicy.poolGuideCovered(request, parsed, serverNow) ?: return@repeat
            // The pool's own window: from its hour minus past_hours to its next hour plus future_hours.
            val serverHour = serverNow / hour * hour
            val sentStart = serverHour - request.pastHours * hour
            val sentEnd = serverHour + hour + request.futureHours * hour
            assertTrue(held.startMs >= sentStart && held.endMs <= sentEnd)
            assertTrue(held.startMs >= parsed.startMs && held.endMs <= parsed.endMs)
            // Same clocks and nothing clamped: everything that was asked for arrives.
            if (serverNow / hour == now / hour && request.pastHours < 6 && request.futureHours < 36) {
                assertEquals(parsed, held)
            }
        }
    }
}
