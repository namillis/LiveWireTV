package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `get_live_streams` through [XtreamClient] and [ProviderRepository]: Live Now events and their gate. */
class LiveNowEventsTest {
    private val streams = """
        [
          {"stream_id":1126879636,"name":"Buffalo Bills @ Los Angeles Rams","epg_channel_id":"nova.skysportsmainevent.uk",
           "category_id":"187","tv_archive":0,
           "pool_event":{"title":"NFL · Week 5","description":"Bills (3-1) at Rams (2-2).","category":"Football",
                         "start":1791850500,"end":1791862200}},
          {"stream_id":"77","name":"Late Game","epg_channel_id":"nova.espn.us","category_id":"187",
           "pool_event":{"title":"","start":1791850500,"end":1791862200}},
          {"stream_id":"5","name":"Sky Sports Main Event","epg_channel_id":"nova.skysportsmainevent.uk","category_id":"3"}
        ]
    """.trimIndent()

    /** A panel answering `pool_info` with [poolInfo] (null: HTTP 404, as a plain panel does). */
    private fun repository(poolInfo: String?): ProviderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action")
            val (code, body) = when (action) {
                "get_live_streams" -> 200 to streams
                "pool_info" -> if (poolInfo == null) 404 to "<html>404</html>" else 200 to poolInfo
                else -> 404 to ""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ProviderRepository(XtreamClient(http), M3uClient(http))
    }

    private val cfg = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv3", password = "p")

    @Test
    fun `a pool listing event_info shows the event`() = runBlocking {
        val channels = repository("""{"pool":1,"version":1,"features":["retry","stop","sources","event_info"]}""").liveChannels(cfg)
        val event = channels.first { it.streamId == "1126879636" }.poolEvent!!
        assertEquals("1126879636", event.channelId)
        assertEquals("NFL · Week 5", event.title)
        assertEquals("Football", event.category)
        assertEquals(1_791_850_500_000L, event.startMs)
        // epg_channel_id is left alone, so catch-up and the guide id behave as before.
        assertEquals("nova.skysportsmainevent.uk", channels.first { it.streamId == "1126879636" }.epgChannelId)
        // A malformed event falls back to the guide; ordinary channels never get one.
        assertNull(channels.first { it.streamId == "77" }.poolEvent)
        assertNull(channels.first { it.streamId == "5" }.poolEvent)
    }

    @Test
    fun `a pool without event_info, or a plain panel, ignores the field`() = runBlocking {
        listOf("""{"pool":1,"features":["retry","stop","sources"]}""", null).forEach { info ->
            val channels = repository(info).liveChannels(cfg)
            assertEquals(3, channels.size)
            channels.forEach { assertNull("${it.streamId} with pool_info $info", it.poolEvent) }
        }
    }

    @Test
    fun `the gate applies to a single category too`() = runBlocking {
        val channels = repository("""{"pool":1,"features":["event_info"]}""").liveChannels(cfg, categoryId = "187")
        assertEquals("NFL · Week 5", channels.first { it.streamId == "1126879636" }.poolEvent?.title)
    }
}
