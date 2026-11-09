package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [XtreamClient.reachable], which decides whether the app moves to the backup provider. */
class XtreamReachableTest {
    private val cfg = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv3", password = "p")

    private fun reachable(code: Int, body: String, retryAfter: String? = null): Boolean = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        XtreamClient(http).reachable(cfg)
    }

    private val busy = """{"error":"Too many requests"}"""

    @Test fun `an accepted login is reachable`() {
        assertTrue(reachable(200, """{"user_info":{"auth":1}}"""))
    }

    @Test fun `a refused login or a server error is not`() {
        assertFalse(reachable(200, """{"user_info":{"auth":0}}"""))
        assertFalse(reachable(503, """{"error":"Pool unavailable"}"""))
    }

    @Test fun `a short 429 is busy, not down, so the app stays on this provider`() {
        assertTrue(reachable(429, busy, retryAfter = "60"))
        assertTrue(reachable(429, busy, retryAfter = "120"))
        assertTrue("no Retry-After: a minute", reachable(429, busy))
    }

    @Test fun `a long 429, like the pool's 15-minute lockout, means it can't be used now`() {
        assertFalse(reachable(429, """{"error":"Too many failed attempts. Try again in 15 minutes."}""", retryAfter = "900"))
        assertFalse(reachable(429, busy, retryAfter = "121"))
    }
}
