package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    /** A cache whose background refreshes and disk I/O run on the test scheduler. */
    private fun TestScope.testCache(dir: java.io.File = tmp.newFolder()) =
        EpgCache(dir, scope = this, io = StandardTestDispatcher(testScheduler))

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
        GuideDownload.Loaded(parseSuperset())
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
            GuideDownload.Loaded(parseSuperset())
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

    /** A guide with one programme on channel "a", titled [title]. */
    private fun guideTitled(title: String): EpgGuide = XmltvParser.parse(
        "<?xml version=\"1.0\"?><tv><channel id=\"a\"><display-name>A</display-name></channel>" +
            "<programme start=\"${fmt(now)}\" stop=\"${fmt(now + hour)}\" channel=\"a\"><title>$title</title></programme></tv>",
        parsedWindow,
        channelIds = null,
    )

    /** One cache call for channel "a" at [nowMs], recording the ETag each load was sent. */
    private suspend fun open(
        c: EpgCache,
        nowMs: Long,
        sent: MutableList<String?>,
        serveStale: Boolean = true,
        window: EpgWindow = guideWindow,
        ttlMs: Long = ttl,
        answer: (ifNoneMatch: String?) -> GuideDownload,
    ): EpgGuide = c.getOrLoad(
        key, providerId, window, parsedWindow, setOf("a"), null, ttlMs, nowMs, serveStale,
    ) { ifNoneMatch -> sent += ifNoneMatch; answer(ifNoneMatch) }

    private fun EpgGuide.title() = programmesFor("a").single().title

    @Test fun `a stale entry is served at once and refreshed in the background`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }

        val stale = open(c, now + ttl + 1, sent) { GuideDownload.Loaded(guideTitled("New"), etag = "\"v2\"") }
        assertEquals("the stale guide comes back without waiting", "Old", stale.title())
        assertEquals(listOf<String?>(null), sent)

        advanceUntilIdle()
        assertEquals("the refresh sent the stored ETag", listOf(null, "\"v1\""), sent)
        val fresh = open(c, now + ttl + 2, sent) { error("fresh again: no load") }
        assertEquals("New", fresh.title())
    }

    @Test fun `a 304 marks the entry fresh without replacing it`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        val updates = mutableListOf<Unit>()
        val watcher = launch { c.updates(providerId).collect { updates += it } }

        open(c, now + ttl + 1, sent) { GuideDownload.NotModified }
        advanceUntilIdle()
        assertEquals(listOf(null, "\"v1\""), sent)
        assertEquals("Old", open(c, now + ttl + 2, sent) { error("fresh after the 304") }.title())
        assertTrue("an unchanged guide doesn't tell the screens to reload", updates.isEmpty())
        watcher.cancel()
    }

    @Test fun `a changed guide from a refresh tells the screens`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        val updates = mutableListOf<Unit>()
        val watcher = launch { c.updates(providerId).collect { updates += it } }
        val other = mutableListOf<Unit>()
        val otherWatcher = launch { c.updates("prov-2").collect { other += it } }

        open(c, now + ttl + 1, sent) { GuideDownload.Loaded(guideTitled("New"), etag = "\"v2\"") }
        advanceUntilIdle()
        assertEquals(1, updates.size)
        assertTrue("another provider's screens aren't told", other.isEmpty())
        watcher.cancel()
        otherWatcher.cancel()
    }

    @Test fun `opens during a refresh don't start another`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        repeat(3) { open(c, now + ttl + 1, sent) { GuideDownload.NotModified } }
        advanceUntilIdle()
        assertEquals(listOf(null, "\"v1\""), sent)
    }

    @Test fun `a failed refresh keeps the stale guide and waits before trying again`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        val later = now + ttl + 1

        assertEquals("Old", open(c, later, sent) { throw java.io.IOException("down") }.title())
        advanceUntilIdle()
        assertEquals("Old", open(c, later + 1_000, sent) { throw java.io.IOException("down") }.title())
        advanceUntilIdle()
        assertEquals("no second attempt within the retry wait", 2, sent.size)

        open(c, later + EpgCache.RETRY_AFTER_MS, sent) { GuideDownload.NotModified }
        advanceUntilIdle()
        assertEquals(3, sent.size)
    }

    @Test fun `a forced refresh waits and still sends the ETag`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }

        // EpgRepository passes a zero TTL with serveStale = false for a forced refresh.
        val unchanged = open(c, now + 1, sent, serveStale = false, ttlMs = 0) { GuideDownload.NotModified }
        assertEquals("Old", unchanged.title())
        val changed = open(c, now + 2, sent, serveStale = false, ttlMs = 0) { GuideDownload.Loaded(guideTitled("New"), etag = "\"v2\"") }
        assertEquals("the new guide comes back from the same call", "New", changed.title())
        assertEquals(listOf(null, "\"v1\"", "\"v1\""), sent)
    }

    @Test fun `an uncovered window downloads in full without an ETag`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        val beyond = EpgWindow(parsedWindow.endMs - hour, parsedWindow.endMs + 4 * hour)
        open(c, now + ttl + 1, sent, window = beyond) { GuideDownload.Loaded(guideTitled("New"), etag = "\"v2\"") }
        assertEquals(listOf(null, null), sent)
    }

    @Test fun `the ETag survives a restart`() = runTest {
        val dir = tmp.newFolder()
        val sent = mutableListOf<String?>()
        open(testCache(dir), now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }

        val restarted = testCache(dir)
        assertEquals("Old", open(restarted, now + ttl + 1, sent) { GuideDownload.NotModified }.title())
        advanceUntilIdle()
        assertEquals(listOf(null, "\"v1\""), sent)
    }

    @Test fun `a refresh that lands after clearProvider is dropped`() = runTest {
        val c = testCache()
        val sent = mutableListOf<String?>()
        open(c, now, sent) { GuideDownload.Loaded(guideTitled("Old"), etag = "\"v1\"") }
        val gate = CompletableDeferred<Unit>()
        c.getOrLoad(key, providerId, guideWindow, parsedWindow, setOf("a"), null, ttl, now + ttl + 1) {
            gate.await(); GuideDownload.Loaded(guideTitled("New"), etag = "\"v2\"")
        }
        runCurrent()
        c.clearProvider(providerId)
        gate.complete(Unit)
        advanceUntilIdle()

        val loads = AtomicInteger()
        fetch(c, setOf("a"), guideWindow, loads, nowMs = now + ttl + 2)
        assertEquals("nothing was stored, so the next open downloads", 1, loads.get())
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
        c.getOrLoad("k1", "prov-1", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); GuideDownload.Loaded(parseSuperset()) }
        c.getOrLoad("k2", "prov-2", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); GuideDownload.Loaded(parseSuperset()) }
        assertEquals(2, loads.get())

        c.clearProvider("prov-1")

        // prov-2 still cached (no reload); prov-1 reloads.
        c.getOrLoad("k2", "prov-2", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); GuideDownload.Loaded(parseSuperset()) }
        assertEquals("prov-2 survives prov-1's delete", 2, loads.get())
        c.getOrLoad("k1", "prov-1", searchWindow, parsedWindow, searchIds, null, ttl, now) { loads.incrementAndGet(); GuideDownload.Loaded(parseSuperset()) }
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
            loads.incrementAndGet(); GuideDownload.Loaded(XmltvParser.parse(dupXml, parsedWindow, channelIds = null))
        }
        assertEquals(setOf("a"), g.channels.map { it.id }.toSet())
        assertEquals("channel a's single programme is not duplicated on cache write", 1, g.programmesFor("a").size)

        // Reopen from cache: still one, not two.
        val again = c.getOrLoad(key, providerId, searchWindow, parsedWindow, setOf("a"), null, ttl, now) {
            loads.incrementAndGet(); GuideDownload.Loaded(XmltvParser.parse(dupXml, parsedWindow, channelIds = null))
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
            loads.incrementAndGet(); GuideDownload.Loaded(XmltvParser.parse(xml, parsedWindow, channelIds = null))
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
            loads.incrementAndGet(); GuideDownload.Loaded(XmltvParser.parse(xml, parsedWindow, channelIds = null))
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
