package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.LiveChannelCache
import com.livewire.tv.feature.providers.data.M3uClient
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.XtreamClient
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** [EpgRepository] asks a pool that lists `guide_window` for its window, and nobody else. */
class EpgGuideWindowTest {

    @get:Rule val tmp = TemporaryFolder()

    private val hour = TimeUnit.HOURS.toMillis(1)
    /** One fixed clock for the TV and the pool. */
    private val now = System.currentTimeMillis()
    private val guideRequests = mutableListOf<HttpUrl>()
    private var poolInfoRequests = 0

    private val xml = "<?xml version=\"1.0\"?><tv><channel id=\"a\"><display-name>A</display-name></channel></tv>"

    /** A provider answering `pool_info` with [features] (null: HTTP 404, a plain panel). */
    private fun repository(features: String?): EpgRepository {
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val (code, body) = when {
                req.url.encodedPath.endsWith("xmltv.php") || req.url.encodedPath.endsWith("guide.xml") -> {
                    guideRequests += req.url
                    200 to xml
                }
                req.url.queryParameter("action") == "pool_info" -> {
                    poolInfoRequests++
                    if (features == null) 404 to "" else 200 to """{"pool":1,"version":1,"features":$features}"""
                }
                else -> 404 to ""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").header("Date", date)
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val providers = ProviderRepository(XtreamClient(http), M3uClient(http), LiveChannelCache(dir = null))
        return EpgRepository(http, providers, EpgCache(tmp.newFolder())).also { it.clock = { now } }
    }

    private val pool = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv3", password = "p")
    private val guideWindow = EpgWindow(now - hour / 2, now + 4 * hour)

    @Test fun `a pool with guide_window is asked for the window the cache keeps`() = runTest {
        val epg = repository("""["retry","stop","sources","event_info","guide_window"]""")
        epg.fetch(pool, guideWindow)
        val expected = EpgCachePolicy.poolGuideRequest(EpgCachePolicy.downloadWindow(guideWindow)!!, now)
        val sent = guideRequests.single()
        assertEquals(expected.pastHours.toString(), sent.queryParameter("past_hours"))
        assertEquals(expected.futureHours.toString(), sent.queryParameter("future_hours"))
        assertEquals("tv3", sent.queryParameter("username"))
    }

    @Test fun `a pool without guide_window gets the full guide request`() = runTest {
        repository("""["retry","stop","sources","event_info"]""").fetch(pool, guideWindow)
        assertNull(guideRequests.single().queryParameter("past_hours"))
        assertNull(guideRequests.single().queryParameter("future_hours"))
    }

    @Test fun `a plain Xtream panel gets the full guide request`() = runTest {
        repository(features = null).fetch(pool, guideWindow)
        assertEquals(1, poolInfoRequests)
        assertNull(guideRequests.single().queryParameter("past_hours"))
    }

    @Test fun `an M3U guide never gets the hours, and pool_info isn't asked`() = runTest {
        val m3u = ProviderConfig(
            id = "m3u", name = "M3U", baseUrl = "https://iptv.example/list.m3u", type = ProviderType.M3U,
            epgUrl = "https://iptv.example/guide.xml",
        )
        repository("""["guide_window"]""").fetch(m3u, guideWindow)
        assertEquals("https://iptv.example/guide.xml", guideRequests.single().toString())
        assertEquals(0, poolInfoRequests)
    }

    @Test fun `a fetch with no window gets the full guide`() = runTest {
        repository("""["guide_window"]""").fetch(pool, window = null)
        assertNull(guideRequests.single().queryParameter("past_hours"))
    }

    @Test fun `a window the pool clamped is cached as the smaller window it is`() = runTest {
        val epg = repository("""["guide_window"]""")
        // Asks for 11 hours back; the pool sends at most 6.
        epg.fetch(pool, EpgWindow(now - 9 * hour, now + hour))
        assertEquals("6", guideRequests.single().queryParameter("past_hours"))

        // Inside what the pool sent: served from the cache.
        epg.fetch(pool, EpgWindow(now - 4 * hour, now))
        assertEquals(1, guideRequests.size)

        // Reaching into the hours the pool cut off: downloaded again, not served short.
        epg.fetch(pool, EpgWindow(now - 7 * hour, now))
        assertEquals(2, guideRequests.size)
        assertTrue(guideRequests.all { it.queryParameter("past_hours") == "6" })
    }
}
