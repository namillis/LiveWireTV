package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LiveChannelCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val pool = ProviderConfig(id = "p1", name = "Pool", baseUrl = "https://iptv.example", username = "tv1", password = "secret")
    private val list = listOf(LiveChannel(streamId = "1", name = "CNN", categoryId = "news"))
    private val newer = list + LiveChannel(
        streamId = "2", name = "ESPN", categoryId = "sports", logoUrl = "https://logo.example/espn.png",
        epgChannelId = "espn.us", archiveDays = 3, number = 206,
    )

    /** A cache whose background work and disk I/O run on the test scheduler. */
    private fun TestScope.cache(dir: File? = tmp.root) = LiveChannelCache(
        dir = dir,
        ttlMs = 60_000L,
        clock = { now },
        // The test scope itself, not backgroundScope: advanceUntilIdle skips background work.
        scope = this,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun files(): List<File> = tmp.root.listFiles()?.toList().orEmpty()

    @Test
    fun `a fresh list is served without loading again`() = runTest {
        val c = cache()
        var loads = 0
        val first = c.get(pool) { loads++; list }
        now += 59_000L
        assertSame(first, c.get(pool) { loads++; newer })
        advanceUntilIdle()
        assertEquals(1, loads)
    }

    @Test
    fun `a stale list is returned at once and refreshed in the background`() = runTest {
        val c = cache(dir = null)
        var loads = 0
        c.get(pool) { loads++; list }
        now += 61_000L
        assertSame(list, c.get(pool) { loads++; newer })
        assertEquals(1, loads)
        advanceUntilIdle()
        assertEquals(2, loads)
        assertEquals(newer, c.get(pool) { loads++; list })
        assertEquals(newer, c.updates(pool).first())
        advanceUntilIdle()
        assertEquals(2, loads)
    }

    @Test
    fun `callers during a background refresh share it`() = runTest {
        val c = cache(dir = null)
        c.get(pool) { list }
        now += 61_000L
        var loads = 0
        repeat(5) { c.get(pool) { loads++; delay(100); newer } }
        advanceUntilIdle()
        assertEquals(1, loads)
    }

    @Test
    fun `concurrent callers share one load`() = runTest {
        val c = cache(dir = null)
        var loads = 0
        val results = (1..5).map {
            async { c.get(pool) { loads++; delay(100); list } }
        }.awaitAll()
        assertEquals(1, loads)
        results.forEach { assertSame(results[0], it) }
    }

    @Test
    fun `an empty result is not cached or written`() = runTest {
        val c = cache()
        var loads = 0
        c.get(pool) { loads++; emptyList() }
        advanceUntilIdle()
        assertTrue(files().isEmpty())
        c.get(pool) { loads++; list }
        c.get(pool) { loads++; list }
        assertEquals(2, loads)
    }

    @Test
    fun `changing the provider's login or URL starts a new entry`() = runTest {
        val c = cache(dir = null)
        var loads = 0
        val load: suspend () -> List<LiveChannel> = { loads++; list }
        c.get(pool, load)
        c.get(pool.copy(password = "changed"), load)
        c.get(pool.copy(baseUrl = "https://other.example"), load)
        c.get(pool.copy(id = "p2"), load)
        assertEquals(4, loads)
    }

    @Test
    fun `clear drops everything in memory`() = runTest {
        val c = cache(dir = null)
        var loads = 0
        val load: suspend () -> List<LiveChannel> = { loads++; list }
        c.get(pool, load)
        c.clear()
        c.get(pool, load)
        assertEquals(2, loads)
    }

    @Test
    fun `the next launch is served from disk, then refreshed`() = runTest {
        cache().get(pool) { list }
        advanceUntilIdle()

        val relaunched = cache()
        var loads = 0
        assertEquals(list, relaunched.get(pool) { loads++; newer })
        assertEquals(0, loads)
        // A disk copy is never fresh, however young: it has no pool events.
        assertNull(relaunched.cached(pool))
        assertEquals(list, relaunched.updates(pool).first())

        advanceUntilIdle()
        assertEquals(1, loads)
        assertEquals(newer, relaunched.cached(pool))
        assertEquals(newer, relaunched.updates(pool).first())
    }

    @Test
    fun `the disk copy keeps every field but the pool event`() = runTest {
        val event = EpgProgramme(channelId = "9", startMs = 1L, stopMs = 2L, title = "Game")
        val withEvent = newer + LiveChannel(streamId = "9", name = "Live 1", categoryId = "live", poolEvent = event)
        cache().get(pool) { withEvent }
        advanceUntilIdle()

        val read = cache().get(pool) { list }
        assertEquals(newer + LiveChannel(streamId = "9", name = "Live 1", categoryId = "live"), read)
    }

    @Test
    fun `M3U lists stay in memory`() = runTest {
        val m3u = pool.copy(id = "m1", type = ProviderType.M3U, baseUrl = "https://iptv.example/get.php?u=tv1&p=secret")
        cache().get(m3u) { list }
        advanceUntilIdle()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `file names and contents never hold the URL or login`() = runTest {
        cache().get(pool) { newer }
        advanceUntilIdle()
        val file = files().single()
        assertTrue(file.name, file.name.matches(Regex("[0-9a-f]{16}_[0-9a-f]{32}\\.json")))
        val text = file.readText()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("tv1"))
        assertFalse(text.contains("iptv.example"))
    }

    @Test
    fun `a new login replaces the provider's old file`() = runTest {
        val c = cache()
        c.get(pool) { list }
        advanceUntilIdle()
        c.get(pool.copy(password = "changed")) { newer }
        advanceUntilIdle()
        assertEquals(1, files().size)
        assertEquals(newer, cache().get(pool.copy(password = "changed")) { list })
    }

    @Test
    fun `clearProvider drops that provider only, in memory and on disk`() = runTest {
        val c = cache()
        val other = pool.copy(id = "p2")
        c.get(pool) { list }
        c.get(other) { list }
        advanceUntilIdle()
        assertEquals(2, files().size)

        c.clearProvider("p1")
        assertEquals(1, files().size)
        var loads = 0
        c.get(pool) { loads++; newer }
        c.get(other) { loads++; newer }
        assertEquals(1, loads)
        assertEquals(list, cache().get(other) { newer })
    }

    @Test
    fun `a load that finishes after clearProvider is not kept`() = runTest {
        val c = cache()
        val gate = CompletableDeferred<Unit>()
        val pending = async { c.get(pool) { gate.await(); list } }
        runCurrent()
        c.clearProvider("p1")
        gate.complete(Unit)
        assertEquals(list, pending.await())
        advanceUntilIdle()
        assertTrue(files().isEmpty())
        var loads = 0
        c.get(pool) { loads++; list }
        assertEquals(1, loads)
    }

    @Test
    fun `a failed refresh keeps the old list and is tried again later`() = runTest {
        val c = cache(dir = null)
        c.get(pool) { list }
        now += 61_000L
        var loads = 0
        var down = true
        val load: suspend () -> List<LiveChannel> = { loads++; if (down) throw IOException("down") else newer }

        assertSame(list, c.get(pool, load))
        runCurrent()
        assertEquals(1, loads)
        // A caller during the wait doesn't start a second refresh.
        assertSame(list, c.get(pool, load))
        runCurrent()
        assertEquals(1, loads)

        advanceTimeBy(LiveChannelCache.RETRY_AFTER_MS + 1)
        assertEquals(2, loads)
        down = false
        advanceTimeBy(LiveChannelCache.RETRY_AFTER_MS + 1)
        assertEquals(3, loads)
        assertEquals(newer, c.updates(pool).first())
        advanceUntilIdle()
        assertEquals(3, loads)
    }

    @Test
    fun `retries stop after a few, then the next caller starts again`() = runTest {
        val c = cache(dir = null)
        c.get(pool) { list }
        now += 61_000L
        var loads = 0
        val failing: suspend () -> List<LiveChannel> = { loads++; throw IOException("down") }

        c.get(pool, failing)
        advanceUntilIdle()
        assertEquals(1 + LiveChannelCache.MAX_RETRIES, loads)
        assertSame(list, c.get(pool, failing))
        advanceUntilIdle()
        // Within RETRY_AFTER_MS of the last failure: no new attempt yet.
        assertEquals(1 + LiveChannelCache.MAX_RETRIES, loads)

        now += LiveChannelCache.RETRY_AFTER_MS
        c.get(pool, failing)
        runCurrent()
        assertEquals(2 + LiveChannelCache.MAX_RETRIES, loads)
    }

    @Test
    fun `fresh loads again only once the copy is stale`() = runTest {
        val c = cache(dir = null)
        c.get(pool) { list }
        assertSame(list, c.fresh(pool) { newer })
        now += 61_000L
        assertEquals(newer, c.fresh(pool) { newer })
        assertEquals(newer, c.cached(pool))
    }

    @Test
    fun `a damaged file is ignored`() = runTest {
        cache().get(pool) { list }
        advanceUntilIdle()
        files().single().writeText("{\"v\":1,\"savedAtMs\":")

        var loads = 0
        assertEquals(newer, cache().get(pool) { loads++; newer })
        assertEquals(1, loads)
    }
}
