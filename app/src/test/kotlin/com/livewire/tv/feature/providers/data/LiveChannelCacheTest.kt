package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveChannelCacheTest {

    private var now = 1_000_000L
    private val cache = LiveChannelCache(ttlMs = 60_000L, clock = { now })
    private val pool = ProviderConfig(id = "p1", name = "Pool", baseUrl = "https://iptv.example", username = "tv1", password = "secret")
    private val list = listOf(LiveChannel(streamId = "1", name = "CNN", categoryId = "news"))

    @Test
    fun `serves the cached list until it expires`() = runTest {
        var loads = 0
        val load: suspend () -> List<LiveChannel> = { loads++; list }
        val first = cache.get(pool, load)
        now += 59_000L
        assertSame(first, cache.get(pool, load))
        assertEquals(1, loads)
        now += 2_000L
        cache.get(pool, load)
        assertEquals(2, loads)
    }

    @Test
    fun `concurrent callers share one load`() = runTest {
        var loads = 0
        val results = (1..5).map {
            async { cache.get(pool) { loads++; delay(100); list } }
        }.awaitAll()
        assertEquals(1, loads)
        results.forEach { assertSame(results[0], it) }
    }

    @Test
    fun `an empty result is not cached`() = runTest {
        var loads = 0
        cache.get(pool) { loads++; emptyList() }
        cache.get(pool) { loads++; list }
        cache.get(pool) { loads++; list }
        assertEquals(2, loads)
    }

    @Test
    fun `changing the provider's login or URL starts a new entry`() = runTest {
        var loads = 0
        val load: suspend () -> List<LiveChannel> = { loads++; list }
        cache.get(pool, load)
        cache.get(pool.copy(password = "changed"), load)
        cache.get(pool.copy(baseUrl = "https://other.example"), load)
        cache.get(pool.copy(id = "p2"), load)
        assertEquals(4, loads)
    }

    @Test
    fun `clear drops everything`() = runTest {
        var loads = 0
        val load: suspend () -> List<LiveChannel> = { loads++; list }
        cache.get(pool, load)
        cache.clear()
        cache.get(pool, load)
        assertEquals(2, loads)
    }
}
