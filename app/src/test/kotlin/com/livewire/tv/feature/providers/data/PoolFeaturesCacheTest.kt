package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** How [ProviderRepository.poolFeatures] caches `pool_info`, including a panel that timed out. */
class PoolFeaturesCacheTest {
    private val poolInfo = """{"pool":1,"features":["retry","stop","sources","event_info","guide_window"]}"""
    private val cfg = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv1", password = "p")

    private var calls = 0
    private var now = 1_000_000L

    /** A panel whose first [timeouts] `pool_info` calls time out; after that it answers as the pool. */
    private fun repository(timeouts: Int): ProviderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            if (calls <= timeouts) throw SocketTimeoutException("slow panel")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(poolInfo.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ProviderRepository(XtreamClient(http), M3uClient(http), LiveChannelCache(dir = null), ProviderFileStore(dir = null)).also { it.clock = { now } }
    }

    @Test
    fun `a timed-out check counts as plain for a minute, then is tried again`() = runBlocking {
        val repo = repository(timeouts = 1)
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        // Within the minute: still plain, and the slow panel isn't asked again on every play.
        now += 30_000
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertEquals(1, calls)
        // After the minute: checked again, and the pool's features come back without a restart.
        now += 31_000
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(2, calls)
    }

    /** A panel that answers its first `pool_info` calls with [refusals] (HTTP code, Retry-After), then as the pool. */
    private fun repositoryRefusing(vararg refusals: Pair<Int, String?>): ProviderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            val refusal = refusals.getOrNull(calls - 1)
            if (refusal != null) {
                builder.code(refusal.first).message("x").body("""{"error":"Too many requests"}""".toResponseBody("application/json".toMediaType()))
                refusal.second?.let { builder.header("Retry-After", it) }
            } else {
                builder.code(200).message("OK").body(poolInfo.toResponseBody("application/json".toMediaType()))
            }
            builder.build()
        }.build()
        return ProviderRepository(XtreamClient(http), M3uClient(http), LiveChannelCache(dir = null), ProviderFileStore(dir = null)).also { it.clock = { now } }
    }

    @Test
    fun `a 429 counts as plain for its Retry-After, then the pool's features come back`() = runBlocking {
        val repo = repositoryRefusing(429 to "90")
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        // While it asked to wait: still plain, and the busy panel isn't asked again.
        now += 89_000
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertEquals(1, calls)
        // After the wait: checked again, and kept for the app run from then on.
        now += 2_000
        assertTrue(PoolSignals.GUIDE_WINDOW in repo.poolFeatures(cfg))
        now += 24 * 60 * 60_000L
        assertTrue(PoolSignals.GUIDE_WINDOW in repo.poolFeatures(cfg))
        assertEquals(2, calls)
    }

    @Test
    fun `a 429 without Retry-After waits a minute`() = runBlocking {
        val repo = repositoryRefusing(429 to null)
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        now += 59_000
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        now += 2_000
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(2, calls)
    }

    @Test
    fun `a 429 wait is kept between a minute and 15 minutes`() = runBlocking {
        val repo = repositoryRefusing(429 to "5", 429 to "86400")
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        now += 30_000
        assertTrue("a 5-second Retry-After still waits the minute", repo.poolFeatures(cfg).isEmpty())
        assertEquals(1, calls)
        now += 31_000
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertEquals(2, calls)
        now += 15 * 60_000L + 1
        assertTrue("a day-long Retry-After is cut to 15 minutes", PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(3, calls)
    }

    @Test
    fun `a Retry-After given as a date is read too`() = runBlocking {
        // An HTTP date is compared with the real clock, so set the repository's clock to it.
        now = System.currentTimeMillis()
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now + 5 * 60_000L).atZone(java.time.ZoneOffset.UTC))
        val repo = repositoryRefusing(429 to date)
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        now += 4 * 60_000L
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertEquals(1, calls)
        now += 2 * 60_000L
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
    }

    @Test
    fun `a server error isn't cached, so the next call asks again`() = runBlocking {
        val repo = repositoryRefusing(503 to null)
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(2, calls)
    }

    @Test
    fun `a real answer is kept for the app run`() = runBlocking {
        val repo = repository(timeouts = 0)
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        now += 24 * 60 * 60_000L
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(1, calls)
    }
}
