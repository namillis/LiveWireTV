package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cross-screen reuse of the single per-provider guide entry: a Guide open and a Search open
 * share one download, whichever runs first, and each is served its own channels and window.
 *
 * The cache stores an all-channels superset parsed over a widened window. These tests build
 * that superset with [XmltvParser] (real parse) and drive [EpgCache] directly with a counting
 * loader, so a second call that avoids the loader is a proven cache hit, not an assumption.
 */
class EpgCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val providerId = "prov-1"
    private val key = "guidekey"

    // A base instant and the two real screen windows around it.
    private val now = 1_700_000_000_000L
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val guideWindow = EpgWindow(now - hour / 2, now + 4 * hour)   // Guide: -30m .. +4h
    private val searchWindow = EpgWindow(now - 2 * hour, now + 6 * hour)  // Search: -2h .. +6h
    private val ttl = TimeUnit.HOURS.toMillis(3)

    // Superset parse window (what EpgRepository would pass); covers both screens in either order.
    private val parsedWindow = EpgCachePolicy.downloadWindow(guideWindow, ttl)!!

    private fun cache() = EpgCache(tmp.newFolder())

    /** A small guide across five channels, one programme each inside every screen's window. */
    private val xmltv = buildString {
        append("<?xml version=\"1.0\"?><tv>")
        for (c in listOf("a", "b", "c", "d", "e")) append("<channel id=\"$c\"><display-name>Ch $c</display-name></channel>")
        // programme per channel from now .. now+1h (inside both windows)
        for (c in listOf("a", "b", "c", "d", "e")) {
            append("<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"$c\"><title>On $c</title></programme>")
        }
        append("</tv>")
    }

    private fun fmt(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "%04d%02d%02d%02d%02d%02d +0000".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND),
        )
    }

    /** Parse the superset the repository caches: all channels, over the widened window. */
    private fun parseSuperset(): EpgGuide = XmltvParser.parse(xmltv, parsedWindow, channelIds = null)

    /** Drive the cache exactly as EpgRepository.fetch does: superset parse, per-caller filter. */
    private suspend fun fetch(
        c: EpgCache,
        requested: Set<String>?,
        window: EpgWindow,
        loads: AtomicInteger,
        nowMs: Long = now,
    ): EpgGuide = c.getOrLoad(
        key = key,
        providerId = providerId,
        window = window,
        parsedWindow = parsedWindow,
        requestedChannelIds = requested,
        parsedChannelIds = null,
        ttlMs = ttl,
        nowMs = nowMs,
    ) {
        loads.incrementAndGet()
        parseSuperset()
    }

    private val guideIds = setOf("a", "b")            // Guide: a couple of categories
    private val searchIds = setOf("a", "b", "c", "d", "e") // Search: every channel

    @Test fun `Guide then Search hits cache - one download, Search sees its own channels`() = runTest {
        val c = cache()
        val loads = AtomicInteger()

        val guide = fetch(c, guideIds, guideWindow, loads)
        assertEquals("Guide triggers the one download", 1, loads.get())
        assertEquals(guideIds, guide.channels.map { it.id }.toSet())

        val search = fetch(c, searchIds, searchWindow, loads)
        assertEquals("Search reuses the cached guide - no second download", 1, loads.get())
        assertEquals(searchIds, search.channels.map { it.id }.toSet())
    }

    @Test fun `Search then Guide hits cache - one download, Guide sees only its channels`() = runTest {
        val c = cache()
        val loads = AtomicInteger()

        val search = fetch(c, searchIds, searchWindow, loads)
        assertEquals(1, loads.get())
        assertEquals(searchIds, search.channels.map { it.id }.toSet())

        val guide = fetch(c, guideIds, guideWindow, loads)
        assertEquals("Guide reuses the cached guide - no second download", 1, loads.get())
        assertEquals(guideIds, guide.channels.map { it.id }.toSet())
    }

    @Test fun `concurrent Guide and Search share one download (single-flight)`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        // A loader that blocks until released, so both callers are in flight together.
        suspend fun blockingFetch(requested: Set<String>, window: EpgWindow) = c.getOrLoad(
            key = key, providerId = providerId, window = window, parsedWindow = parsedWindow,
            requestedChannelIds = requested, parsedChannelIds = null, ttlMs = ttl, nowMs = now,
        ) {
            loads.incrementAndGet()
            gate.await()
            parseSuperset()
        }

        val g = async { blockingFetch(guideIds, guideWindow) }
        val s = async { blockingFetch(searchIds, searchWindow) }
        gate.complete(Unit)
        val guide = g.await()
        val search = s.await()

        assertEquals("Only one download runs for two concurrent opens", 1, loads.get())
        assertEquals(guideIds, guide.channels.map { it.id }.toSet())
        assertEquals(searchIds, search.channels.map { it.id }.toSet())
    }

    @Test fun `subset filtering returns only the requested channels`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        fetch(c, null, searchWindow, loads) // seed the superset (all channels)
        val only = fetch(c, setOf("c"), searchWindow, loads)
        assertEquals(1, loads.get())
        assertEquals(setOf("c"), only.channels.map { it.id }.toSet())
        assertEquals(1, only.programmeCount)
        assertTrue(only.programmesFor("c").isNotEmpty())
        assertTrue(only.programmesFor("a").isEmpty())
    }

    @Test fun `a request window the cache does not cover triggers a reload`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        fetch(c, guideIds, guideWindow, loads)
        assertEquals(1, loads.get())

        // A window running far past the parsed end is not covered -> reload.
        val beyond = EpgWindow(parsedWindow.endMs - hour, parsedWindow.endMs + 4 * hour)
        fetch(c, guideIds, beyond, loads)
        assertEquals("An uncovered window reloads", 2, loads.get())
    }

    @Test fun `a stale entry (past TTL) reloads`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        fetch(c, guideIds, guideWindow, loads, nowMs = now)
        assertEquals(1, loads.get())
        fetch(c, guideIds, guideWindow, loads, nowMs = now + ttl + 1)
        assertEquals("A stale entry reloads", 2, loads.get())
    }

    @Test fun `clearProvider invalidates the entry so the next open reloads`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        fetch(c, searchIds, searchWindow, loads)
        assertEquals(1, loads.get())

        c.clearProvider(providerId)

        fetch(c, searchIds, searchWindow, loads)
        assertEquals("After delete, the guide is downloaded again", 2, loads.get())
    }

    @Test fun `clearProvider leaves another provider's entry intact`() = runTest {
        val c = cache()
        val loads = AtomicInteger()
        // Two providers, distinct keys.
        c.getOrLoad("k1", "prov-1", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); parseSuperset() }
        c.getOrLoad("k2", "prov-2", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); parseSuperset() }
        assertEquals(2, loads.get())

        c.clearProvider("prov-1")

        // prov-2 still cached (no reload); prov-1 reloads.
        c.getOrLoad("k2", "prov-2", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); parseSuperset() }
        assertEquals("prov-2 survives prov-1's delete", 2, loads.get())
        c.getOrLoad("k1", "prov-1", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); parseSuperset() }
        assertEquals("prov-1 reloads after its delete", 3, loads.get())
    }

    @Test fun `no duplicate programmes when a channel id repeats in the feed`() = runTest {
        // A feed listing channel "a" twice (providers do this) must not double its programmes.
        val dupXml = "<?xml version=\"1.0\"?><tv>" +
            "<channel id=\"a\"><display-name>A</display-name></channel>" +
            "<channel id=\"a\"><display-name>A again</display-name></channel>" +
            "<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"a\"><title>On a</title></programme>" +
            "</tv>"
        val c = cache()
        val loads = AtomicInteger()
        val g = c.getOrLoad(key, providerId, searchWindow, parsedWindow, setOf("a"), null, ttl, now) {
            loads.incrementAndGet(); XmltvParser.parse(dupXml, parsedWindow, channelIds = null)
        }
        assertEquals(setOf("a"), g.channels.map { it.id }.toSet())
        assertEquals("channel a's single programme is not duplicated on cache write", 1, g.programmesFor("a").size)

        // Reopen from cache: still one, not two.
        val again = c.getOrLoad(key, providerId, searchWindow, parsedWindow, setOf("a"), null, ttl, now) {
            loads.incrementAndGet(); XmltvParser.parse(dupXml, parsedWindow, channelIds = null)
        }
        assertEquals(1, loads.get())
        assertEquals(1, again.programmesFor("a").size)
    }

    @Test fun `programmes outside the caller window are filtered out on read`() = runTest {
        // Superset holds a programme well after the Guide window; a Guide read must drop it.
        val far = now + 5 * hour // inside the parsed superset (ends now+13h) but outside guideWindow (+4h)
        val xml = "<?xml version=\"1.0\"?><tv>" +
            "<channel id=\"a\"><display-name>A</display-name></channel>" +
            "<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"a\"><title>Soon</title></programme>" +
            "<programme start=\"${fmt(far)}\" stop=\"${fmt(far + hour)}\" channel=\"a\"><title>Later</title></programme>" +
            "</tv>"
        val c = cache()
        val loads = AtomicInteger()
        val guide = c.getOrLoad(key, providerId, guideWindow, parsedWindow, setOf("a"), null, ttl, now) {
            loads.incrementAndGet(); XmltvParser.parse(xml, parsedWindow, channelIds = null)
        }
        assertEquals("only the programme within the Guide window survives the read filter", 1, guide.programmesFor("a").size)
        assertEquals("Soon", guide.programmesFor("a").first().title)
    }

    @Test fun `disk round-trip preserves values and interns repeated strings on decode`() = runTest {
        // Two channels carry programmes with the same title and description. Written by one
        // cache, read back by a SECOND cache pointed at the same folder so the read must go
        // through the streamed on-disk decode (not the in-memory entry).
        val dir = tmp.newFolder()
        val xml = "<?xml version=\"1.0\"?><tv>" +
            "<channel id=\"a\"><display-name>A</display-name></channel>" +
            "<channel id=\"b\"><display-name>B</display-name></channel>" +
            "<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"a\"><title>News</title><desc>Rolling coverage.</desc></programme>" +
            "<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"b\"><title>News</title><desc>Rolling coverage.</desc></programme>" +
            "</tv>"
        val writer = EpgCache(dir)
        val loads = AtomicInteger()
        writer.getOrLoad(key, providerId, searchWindow, parsedWindow, null, null, ttl, now) {
            loads.incrementAndGet(); XmltvParser.parse(xml, parsedWindow, channelIds = null)
        }
        assertEquals(1, loads.get())

        // Fresh cache instance: forces the streamed disk decode, not the memory hit.
        val reader = EpgCache(dir)
        val guide = reader.getOrLoad(key, providerId, searchWindow, parsedWindow, null, null, ttl, now) {
            loads.incrementAndGet(); error("should have been served from disk")
        }
        assertEquals("served from disk, loader not called again", 1, loads.get())

        val pa = guide.programmesFor("a").single()
        val pb = guide.programmesFor("b").single()
        // Values preserved through the round-trip.
        assertEquals("News", pa.title)
        assertEquals("Rolling coverage.", pa.description)
        // Interned on decode: equal strings across channels share one instance.
        assertSame("titles interned on decode", pa.title, pb.title)
        assertSame("descriptions interned on decode", pa.description, pb.description)
    }
}
