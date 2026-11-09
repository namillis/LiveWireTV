package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `get_live_streams` and `get_live_categories` decoded from the stream into typed rows. */
class XtreamListDecodeTest {

    private val cfg = ProviderConfig(id = "p", name = "P", baseUrl = "https://iptv.example", username = "u", password = "p")

    /** A panel answering every call with [body] and HTTP [code]. */
    private fun client(body: String, code: Int = 200) = XtreamClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    private fun channels(body: String, code: Int = 200): List<LiveChannel> = runBlocking { client(body, code).liveChannels(cfg) }

    @Test fun `fields read the same whether sent as strings or numbers`() {
        val asStrings = channels(
            """[{"num":"7","name":"CNN","stream_id":"101","stream_icon":"https://logo/cnn.png","epg_channel_id":"cnn.us",
                "category_id":"5","tv_archive":"1","tv_archive_duration":"3"}]""",
        )
        val asNumbers = channels(
            """[{"num":7,"name":"CNN","stream_id":101,"stream_icon":"https://logo/cnn.png","epg_channel_id":"cnn.us",
                "category_id":5,"tv_archive":1,"tv_archive_duration":3}]""",
        )
        val expected = LiveChannel(
            streamId = "101", name = "CNN", logoUrl = "https://logo/cnn.png", epgChannelId = "cnn.us",
            categoryId = "5", archiveDays = 3, number = 7,
        )
        assertEquals(listOf(expected), asStrings)
        assertEquals(listOf(expected), asNumbers)
    }

    @Test fun `fractional and odd values map as before`() {
        val ch = channels(
            """[{"num":"12.0","name":"A","stream_id":1,"category_id":"1","tv_archive":1.0,"tv_archive_duration":"x"},
               {"num":0,"name":"B","stream_id":2,"category_id":"1","tv_archive":0,"tv_archive_duration":7},
               {"num":null,"name":"C","stream_id":3,"category_id":null,"tv_archive":true,"stream_icon":"","epg_channel_id":"  "}]""",
        )
        assertEquals(12, ch[0].number)
        assertEquals("archive flag 1.0 counts, but an unreadable duration is 0 days", 0, ch[0].archiveDays)
        assertNull("num 0 means no number", ch[1].number)
        assertEquals("no archive when tv_archive is 0", 0, ch[1].archiveDays)
        assertNull(ch[2].number)
        assertEquals("", ch[2].categoryId)
        assertNull("blank icon is no icon", ch[2].logoUrl)
        assertNull("blank guide id is no guide id", ch[2].epgChannelId)
    }

    @Test fun `unknown fields, nested values and objects in a text field are skipped`() {
        val ch = channels(
            """[{"stream_id":"9","name":"X","category_id":["1","2"],"direct_source":"http://x/y","custom_sid":{"a":[1,2,{"b":null}]},
                "added":"1700000000","is_adult":0,"thumbnail":"","category_ids":[1,2]}]""",
        )
        assertEquals(1, ch.size)
        assertEquals("9", ch[0].streamId)
        assertEquals("an array where text is expected reads as nothing", "", ch[0].categoryId)
    }

    @Test fun `pool events still come through`() {
        val ch = channels(
            """[{"stream_id":5,"name":"Live 1","category_id":"187",
                "pool_event":{"title":"NFL","start":1791850500,"end":1791862200}}]""",
        )
        val event = ch.single().poolEvent!!
        assertEquals("NFL", event.title)
        assertEquals(1791850500L * 1000, event.startMs)
        assertEquals("5", event.channelId)
    }

    @Test fun `a bad login object, an error page or an HTTP error give an empty list`() {
        assertTrue(channels("""{"user_info":{"auth":0}}""").isEmpty())
        assertTrue(channels("<html>Forbidden</html>").isEmpty())
        assertTrue(channels("[]").isEmpty())
        assertTrue(channels("""[{"stream_id":1,"name":"A"}]""", code = 403).isEmpty())
        assertTrue("a cut-off body", channels("""[{"stream_id":1,"name":"A"},{"stream_""").isEmpty())
    }

    @Test fun `text with escapes and non-ASCII names decodes`() {
        val ch = channels("""[{"stream_id":"1","name":"Caf\u00e9 \"Live\" · Ñ","category_id":"1"}]""")
        assertEquals("Café \"Live\" · Ñ", ch.single().name)
    }

    @Test fun `categories decode from strings or numbers`() {
        val cats = runBlocking {
            client("""[{"category_id":"187","category_name":"Live Sports","parent_id":0},{"category_id":692,"category_name":"Local DC"}]""")
                .liveCategories(cfg)
        }
        assertEquals(listOf(ProviderCategory("187", "Live Sports"), ProviderCategory("692", "Local DC")), cats)
    }
}
