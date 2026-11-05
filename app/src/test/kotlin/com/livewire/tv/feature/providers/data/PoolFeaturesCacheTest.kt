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
    private val poolInfo = """{"pool":1,"features":["retry","stop","sources","event_info"]}"""
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
        return ProviderRepository(XtreamClient(http), M3uClient(http)).also { it.clock = { now } }
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

    @Test
    fun `a real answer is kept for the app run`() = runBlocking {
        val repo = repository(timeouts = 0)
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        now += 24 * 60 * 60_000L
        assertTrue(PoolSignals.SOURCES in repo.poolFeatures(cfg))
        assertEquals(1, calls)
    }
}
